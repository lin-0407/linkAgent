package com.link.linkagent.creator.creation.aivideo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.aivideo.mapper.CreationVideoMapper;
import com.link.linkagent.creator.creation.aivideo.model.VideoModels;
import com.link.linkagent.creator.creation.aivideo.provider.VideoGenerationProvider;
import com.link.linkagent.creator.creation.aivideo.service.*;
import com.link.linkagent.creator.creation.asset.mapper.CreationAssetMapper;
import com.link.linkagent.creator.creation.asset.service.AssetMediaPreparer;
import com.link.linkagent.creator.creation.asset.service.CreationAssetPersistence;
import com.link.linkagent.creator.creation.asset.service.CreationAssetService;
import com.link.linkagent.creator.creation.mapper.*;
import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import com.link.linkagent.creator.creation.billing.BillingExchangeRate;
import com.link.linkagent.creator.creation.service.CreationBillingProperties;
import com.link.linkagent.creator.creation.service.CreationCapabilityGate;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.service.*;
import com.link.linkagent.creator.media.config.ObjectStorageProperties;
import com.link.linkagent.creator.media.storage.*;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真实 MySQL 验证 AI 视频受理、费用、异步任务续查、片段登记与新版本保存。
 * 供应商与媒体规范化使用受控替身，因此这里不证明真实视频供应商已验收。
 */
@Testcontainers
class CreationVideoIntegrationTest {
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "video-isolated-test")
            .withEnv("MYSQL_ROOT_HOST", "%")
            .withEnv("MYSQL_DATABASE", "link_agent")
            .withLabels(Map.of("linkagent.scope", "creation-s4-video-test"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("sql/init.sql"),
                    "/docker-entrypoint-initdb.d/01-init.sql")
            .withExposedPorts(3306)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    static final ObjectMapper JSON = new ObjectMapper();
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager transactions;
    static SqlSessionTemplate session;
    static MediaUploadMapper media;
    static CreationRuntimeMapper runs;
    static CreationWorkspaceMapper workspace;
    static StoryboardMapper boards;
    static CreationVideoMapper videos;
    static CreatorTaskMapper tasks;
    static CreationRuntimeService runtime;
    static StoryboardPersistence storyboards;
    static CreationVideoPersistence videoPersistence;
    static StoryboardCodec codec;
    static StoryboardCompiler compiler;
    static ValidatorFactory validators;
    static LocalStorage storage;
    static ObjectStorageProperties storageProperties;
    static Path workRoot;
    static CreationAssetService assetService;

    CreationVideoProperties settings;
    CreationBillingProperties prices;
    StubProvider provider;
    AssetMediaPreparer preparer;
    CreationVideoService service;
    CreationVideoWorker worker;

    @BeforeAll
    static void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:mysql://" + MYSQL.getHost() + ":"
                + MYSQL.getMappedPort(3306) + "/link_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "root", "video-isolated-test");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> type : List.of(MediaUploadMapper.class, CreationRuntimeMapper.class, CreationWorkspaceMapper.class,
                CreationMediaMapper.class, CreationPackageMapper.class, CreatorTaskMapper.class,
                CreationAssetMapper.class, StoryboardMapper.class, CreationVideoMapper.class)) {
            configuration.addMapper(type);
        }
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.afterPropertiesSet();
        session = new SqlSessionTemplate(factory.getObject());
        media = session.getMapper(MediaUploadMapper.class);
        runs = session.getMapper(CreationRuntimeMapper.class);
        workspace = session.getMapper(CreationWorkspaceMapper.class);
        boards = session.getMapper(StoryboardMapper.class);
        videos = session.getMapper(CreationVideoMapper.class);
        tasks = session.getMapper(CreatorTaskMapper.class);
        runtime = tx(new CreationRuntimeService(media, runs, workspace,
                session.getMapper(CreationMediaMapper.class), session.getMapper(CreationPackageMapper.class), JSON));
        validators = Validation.buildDefaultValidatorFactory();
        codec = new StoryboardCodec(JSON, validators.getValidator());
        compiler = new StoryboardCompiler(JSON);
        storage = new LocalStorage();
        storageProperties = new ObjectStorageProperties();
        storageProperties.setBucket("isolated-video");
        workRoot = Files.createTempDirectory("video-work-");
        assetService = new CreationAssetService(session.getMapper(CreationAssetMapper.class),
                tx(new CreationAssetPersistence(session.getMapper(CreationAssetMapper.class))),
                storageProperties, storage, JSON);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (validators != null) validators.close();
        try (var paths = Files.walk(workRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @BeforeEach
    void boundaries() throws Exception {
        settings = new CreationVideoProperties();
        settings.setEnabled(true);
        settings.setBaseUrl("https://video.example.invalid/api");
        settings.setApiKey("test-key");
        settings.setModel("test-video-1");
        settings.setUsdPerSecond(new BigDecimal("0.10"));
        settings.setSupportedDurationsSeconds(List.of(5, 10));
        settings.setPollInterval(Duration.ofMillis(10));
        settings.setTaskTimeout(Duration.ofSeconds(10));
        settings.validate();
        prices = new CreationBillingProperties();
        provider = new StubProvider();
        preparer = mock(AssetMediaPreparer.class);
        when(preparer.prepare(any(Path.class), any(Path.class), eq("VIDEO_CLIP"), any())).thenAnswer(invocation -> {
            Path output = invocation.getArgument(1);
            Path normalized = output.resolve("normalized.mp4");
            Files.write(normalized, mp4());
            long durationMs = provider.clipDurationMs;
            return new AssetMediaPreparer.Result(videoProbe(durationMs), videoProbe(durationMs),
                    List.of(new AssetMediaPreparer.DerivedFile("NORMALIZED", "normalized.mp4", "video/mp4", normalized)),
                    durationMs, 1280, 720, false);
        });
        storyboards = tx(new StoryboardPersistence(boards, media, tasks, runs, workspace, runtime, assetService,
                codec, JSON, prices, mock(StoryboardTextGenerator.class)));
        videoPersistence = tx(new CreationVideoPersistence(videos, runtime, storyboards, assetService, media, JSON));
        service = new CreationVideoService(boards, storyboards, codec, media, videoPersistence, runtime,
                settings, prices, List.of(provider), JSON);
        var renderer = new FixedProjectRendererProperties();
        renderer.setWorkRoot(workRoot.toString());
        worker = new CreationVideoWorker(videos, videoPersistence, settings, runtime, prices, boards, codec,
                compiler, preparer, assetService, storage, storageProperties, renderer, List.of(provider), JSON);
    }

    @AfterEach
    void closeWorker() { worker.close(); }

    @Test
    void capabilityIsMaskedAndExplainsMissingConfiguration() {
        var capability = service.capability();
        assertThat(capability.configured()).isTrue();
        assertThat(capability.provider()).isEqualTo("compatible");
        assertThat(capability.model()).isEqualTo("test-video-1");
        assertThat(capability.durationsSeconds()).containsExactly(5, 10);
        // 记录结构里没有服务地址和密钥字段，页面拿不到凭据。
        assertThat(Arrays.stream(capability.getClass().getRecordComponents()).map(component -> component.getName()))
                .doesNotContain("apiKey", "baseUrl");

        var disabledService = new CreationVideoService(boards, storyboards, codec, media, videoPersistence, runtime,
                new CreationVideoProperties(), prices, List.of(), JSON);
        var disabled = disabledService.capability();
        assertThat(disabled.configured()).isFalse();
        assertThat(disabled.blockers()).anyMatch(text -> text.contains("未启用"));
    }

    @Test
    void estimateIsReadOnlyAndGenerateCreatesOneAuthorizedRun() {
        var fixture = fixture("AI_VIDEO", List.of(), "海边日落，缓慢推进", "NO_DIALOGUE", "NO_DIALOGUE", 2000);
        var estimate = service.estimate(fixture.task, "default", fixture.boardId, "SELECTED", false);
        assertThat(estimate.configured()).isTrue();
        assertThat(estimate.blockers()).isEmpty();
        assertThat(estimate.sceneCount()).isEqualTo(1);
        assertThat(estimate.generateCount()).isEqualTo(1);
        assertThat(estimate.scenes().getFirst().requestedSeconds()).isEqualTo(5);
        assertThat(estimate.estimatedMaxCostCny()).isEqualByComparingTo("3.600000");
        assertThat(count("creator_creation_run", fixture.task)).isZero();

        var prep = service.generate(fixture.task, "default", "video-key", fixture.boardId,
                new VideoModels.Generate(fixture.revision, new BigDecimal("10"), "SELECTED", false));
        assertThat(prep.status()).isEqualTo("QUEUED");
        assertThat(prep.scenes()).hasSize(1);
        assertThat(prep.scenes().getFirst().status()).isEqualTo("QUEUED");
        assertThat(count("creator_creation_video_job", fixture.task)).isEqualTo(1);
        assertThat(count("creator_creation_video", fixture.task)).isEqualTo(1);

        var replay = service.generate(fixture.task, "default", "video-key", fixture.boardId,
                new VideoModels.Generate(fixture.revision, new BigDecimal("10"), "SELECTED", false));
        assertThat(replay.runId()).isEqualTo(prep.runId());
        assertThat(count("creator_creation_run", fixture.task)).isEqualTo(1);
        assertThatThrownBy(() -> service.generate(fixture.task, "default", "video-key", fixture.boardId,
                new VideoModels.Generate(fixture.revision, new BigDecimal("9"), "SELECTED", false)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void workerSubmitsPollsAndSavesPrivateClipWithNewBoard() throws Exception {
        var fixture = fixture("AI_VIDEO", List.of(), "海边日落，缓慢推进", "NO_DIALOGUE", "NO_DIALOGUE", 2000);
        var prep = service.generate(fixture.task, "default", "video-key", fixture.boardId,
                new VideoModels.Generate(fixture.revision, new BigDecimal("10"), "SELECTED", false));

        worker.process(videos.job(fixture.task, "default", prep.runId()).orElseThrow());

        var run = runs.run(fixture.task, "default", prep.runId()).orElseThrow();
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(provider.submitted.get()).isEqualTo(1);
        assertThat(provider.queried.get()).isPositive();
        var view = service.view(fixture.task, "default", prep.runId());
        assertThat(view.storyboardResultId()).isNotNull();
        assertThat(view.requiresUserDecision()).isFalse();
        assertThat(view.scenes().getFirst().status()).isEqualTo("READY");

        var row = videos.scene(prep.runId(), "s1").orElseThrow();
        assertThat(row.getStatus()).isEqualTo("READY");
        assertThat(row.getVideoDurationMs()).isEqualTo(5000);
        assertThat(row.getProviderTaskId()).isEqualTo("task-1");
        assertThat(row.getVideoAssetId()).isNotBlank();
        var assetKind = jdbc.queryForObject("SELECT kind FROM creator_creation_asset WHERE asset_id=?", String.class, row.getVideoAssetId());
        var assetStatus = jdbc.queryForObject("SELECT status FROM creator_creation_asset WHERE asset_id=?", String.class, row.getVideoAssetId());
        assertThat(assetKind).isEqualTo("VIDEO_CLIP");
        assertThat(assetStatus).isEqualTo("READY");
        var metadata = JSON.readTree(jdbc.queryForObject("SELECT metadata_json FROM creator_creation_asset WHERE asset_id=?",
                String.class, row.getVideoAssetId()));
        assertThat(metadata.path("generated").path("kind").asText()).isEqualTo("AI_VIDEO");
        assertThat(metadata.path("generated").path("provider").asText()).isEqualTo("compatible");
        assertThat(metadata.path("generated").path("promptSha256").asText()).hasSize(64);
        assertThat(metadata.path("generated").path("requestedSeconds").asInt()).isEqualTo(5);

        var saved = boards.board(fixture.task, "default", view.storyboardResultId()).orElseThrow();
        assertThat(saved.parentStoryboardId()).isEqualTo(fixture.boardId);
        var scene = codec.candidate(codec.read(saved.documentJson()).path("candidate")).scenes().getFirst();
        assertThat(scene.layout()).isEqualTo("AI_VIDEO");
        assertThat(scene.visualAssetId()).isEqualTo(row.getVideoAssetId());
        assertThat(scene.sourceInMs()).isZero();
        assertThat(scene.sourceOutMs()).isEqualTo(2000);
        assertThat(scene.visualPrompt()).isEqualTo("海边日落，缓慢推进");
        assertThat(scene.body()).isEmpty();
        var brief = storyboards.brief(fixture.task, "default", saved.briefId());
        var check = compiler.check(saved.storyboardId(), codec.read(brief.documentJson()), new Candidate(List.of(scene)),
                storyboards.frozen(saved));
        assertThat(check.problems()).isEmpty();
        assertThat(check.renderable()).isTrue();

        assertThat(chargeStatus(fixture.task, "video-gen-" + prep.runId() + "-s1")).isEqualTo("SETTLED");
        assertThat(runtime.snapshot(fixture.task, "default").budget().knownCny()).isPositive();

        // 第二次推进不能重复提交或再生成版本。
        int boardsBefore = count("creator_creation_storyboard", fixture.task);
        worker.process(videos.job(fixture.task, "default", prep.runId()).orElseThrow());
        assertThat(provider.submitted.get()).isEqualTo(1);
        assertThat(count("creator_creation_storyboard", fixture.task)).isEqualTo(boardsBefore);
    }

    @Test
    void clipShorterThanSceneReturnsProposalAndCanBeReusedAfterShortening() {
        provider.clipDurationMs = 1000;
        var fixture = fixture("AI_VIDEO", List.of(), "海边日落", "NO_DIALOGUE", "NO_DIALOGUE", 2000);
        var prep = service.generate(fixture.task, "default", "video-key", fixture.boardId,
                new VideoModels.Generate(fixture.revision, new BigDecimal("10"), "SELECTED", false));
        worker.process(videos.job(fixture.task, "default", prep.runId()).orElseThrow());

        var view = service.view(fixture.task, "default", prep.runId());
        assertThat(view.requiresUserDecision()).isTrue();
        assertThat(view.proposals()).extracting(VideoModels.Proposal::code).contains("SHORTEN_SCENE");
        assertThat(view.proposals().getFirst().proposedDurationMs()).isEqualTo(1000);
        assertThat(view.storyboardResultId()).isNull();
        var row = videos.scene(prep.runId(), "s1").orElseThrow();
        assertThat(row.getStatus()).isEqualTo("NEEDS_REVIEW");
        assertThat(row.getErrorCode()).isEqualTo("VIDEO_CLIP_TOO_SHORT");
        assertThat(row.getVideoAssetId()).isNotBlank();
        assertThat(count("creator_creation_storyboard", fixture.task)).isEqualTo(1);
        // 付费片段仍登记为私有素材，缩短场景后可以直接复用，不必再次付费。
        assertThat(count("creator_creation_asset", fixture.task)).isEqualTo(1);
        assertThat(chargeStatus(fixture.task, "video-gen-" + prep.runId() + "-s1")).isEqualTo("SETTLED");
        assertThat(runs.run(fixture.task, "default", prep.runId()).orElseThrow().status()).isEqualTo("COMPLETED");

        var shorter = storyboards.saveBrief(fixture.task, "default", "short-brief", new BriefInput(revision(fixture.task),
                "缩短后重试", "讲清楚一个画面", "第一次做视频的人", 1000, "NO_DIALOGUE", "USER_PROVIDED", List.of(), ""));
        var shorterBoard = storyboards.save(fixture.task, "default", "short-board", new Save(revision(fixture.task),
                shorter.briefId(), null, new Candidate(List.of(new Scene("s1", "AI_VIDEO", "开场", List.of(), "", 1000,
                null, null, null, "CONTAIN", "NO_DIALOGUE", null, List.of(), List.of("brief:user"), "海边日落")))));
        var estimate = service.estimate(fixture.task, "default", shorterBoard.storyboardId(), "SELECTED", false);
        assertThat(estimate.reuseCount()).isEqualTo(1);
        assertThat(estimate.generateCount()).isZero();
        assertThat(estimate.estimatedMaxCostCny()).isEqualByComparingTo("0");
        var second = service.generate(fixture.task, "default", "video-key-2", shorterBoard.storyboardId(),
                new VideoModels.Generate(revision(fixture.task), new BigDecimal("0.000001"), "SELECTED", false));
        worker.process(videos.job(fixture.task, "default", second.runId()).orElseThrow());
        assertThat(provider.submitted.get()).isEqualTo(1);
        assertThat(runs.run(fixture.task, "default", second.runId()).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(service.view(fixture.task, "default", second.runId()).storyboardResultId()).isNotNull();
    }

    @Test
    void savingVideoOnVoiceBoardKeepsNarrationAssets() throws Exception {
        String task = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO creator_task(task_id,user_id,task_name,video_type,status) VALUES (?,?,'混合配音与视频','知识分享','DRAFT')",
                task, "default");
        media.adoptMediaWorkspace(task, "default");
        long revision = runs.workspace(task, "default").orElseThrow().workspaceRevision();
        String text = "同一段配音文稿";
        var brief = storyboards.saveBrief(task, "default", "brief-" + task, new BriefInput(revision,
                "混合制作", "先配音再生成画面", "第一次做视频的人", 4000, "TTS", "USER_PROVIDED", List.of(), ""));
        String ttsId = "tts-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String metadata = """
                {"normalized":{"format":{"duration":1.5},"streams":[{"codec_type":"audio","codec_name":"pcm_s16le",
                "sample_rate":"48000","channels":2,"duration":1.5}]},
                "generated":{"kind":"TTS","provider":"dashscope","model":"cosyvoice-v2","textSha256":"%s"}}
                """.formatted(StoryboardCodec.digest(text));
        String objects = JSON.writeValueAsString(List.of(Map.of("role", "NORMALIZED", "objectKey", "normalized/tts.wav",
                "contentType", "audio/wav", "bytes", 1000, "sha256", "a".repeat(64))));
        jdbc.update("""
                INSERT INTO creator_creation_asset(asset_id,task_id,owner_id,idempotency_key,kind,file_name,content_type,
                    expected_bytes,expected_sha256,bucket_name,source_key,status,source_sha256,metadata_json,objects_json)
                VALUES (?,?,'default',?,'NARRATION','tts.wav','audio/wav',1000,?,'isolated-video','normalized/tts.wav',
                    'READY',?,?,?)
                """, ttsId, task, "tts-key-" + ttsId, "a".repeat(64), "a".repeat(64), metadata, objects);
        var ttsAsset = new Asset(ttsId, "NARRATION", "isolated-video", "normalized/tts.wav", "a".repeat(64),
                1000, "audio/wav", JSON.readTree(metadata));
        var ttsScene = new Scene("s1", "TITLE_CARD", "开场", List.of(), text, 2000, null, null, null,
                "CONTAIN", "TTS", ttsId, List.of(new Cue(0, 1200, text)), List.of("brief:user"));
        var aiScene = new Scene("s2", "AI_VIDEO", "结尾", List.of(), text, 2000, null, null, null,
                "CONTAIN", "TTS", ttsId, List.of(new Cue(0, 1200, text)), List.of("brief:user"), "海边日落");
        String parent = "voice-board-" + task.substring(0, 8);
        ObjectNode document = JSON.createObjectNode().put("schemaVersion", "storyboard.v1").put("storyboardId", parent)
                .put("briefId", brief.briefId()).put("briefRevision", brief.revision())
                .put("templateVersion", "controlled.v1").put("subtitleTimingSource", "ASR_ALIGNED");
        document.set("candidate", JSON.valueToTree(new Candidate(List.of(ttsScene, aiScene))));
        boards.insertBoard(new Board(parent, task, "default", 1, brief.briefId(), null, "voice-run",
                "voice-board-key-" + task, "voice-hash", codec.hash(document), JSON.writeValueAsString(document),
                JSON.writeValueAsString(List.of(ttsAsset)), null));
        boards.reference(parent, ttsId, task, "default");
        runtime.initialize(task, "default", "budget-" + task, revision(task), new BigDecimal("20"));

        var prep = service.generate(task, "default", "video-key", parent, new VideoModels.Generate(revision(task),
                new BigDecimal("10"), "SELECTED", false));
        worker.process(videos.job(task, "default", prep.runId()).orElseThrow());

        var savedId = service.view(task, "default", prep.runId()).storyboardResultId();
        assertThat(savedId).isNotNull();
        var saved = boards.board(task, "default", savedId).orElseThrow();
        assertThat(saved.assetSnapshotJson()).contains(ttsId).contains(videos.scene(prep.runId(), "s2").orElseThrow().getVideoAssetId());
        var candidate = codec.candidate(codec.read(saved.documentJson()).path("candidate"));
        assertThat(candidate.scenes().get(0).narrationAssetId()).isEqualTo(ttsId);
        assertThat(candidate.scenes().get(1).layout()).isEqualTo("AI_VIDEO");
        assertThat(candidate.scenes().get(1).narrationAssetId()).isEqualTo(ttsId);
        assertThat(codec.read(saved.documentJson()).path("subtitleTimingSource").asText()).isEqualTo("ASR_ALIGNED");
        var check = compiler.check(saved.storyboardId(), codec.read(storyboards.brief(task, "default", saved.briefId()).documentJson()),
                candidate, storyboards.frozen(saved));
        assertThat(check.problems()).isEmpty();
        assertThat(check.renderable()).isTrue();
    }

    @Test
    void explicitRejectionSettlesZeroAndDoesNotBlockTheTask() {
        provider.reject = true;
        var fixture = fixture("AI_VIDEO", List.of(), "海边日落", "NO_DIALOGUE", "NO_DIALOGUE", 2000);
        var prep = service.generate(fixture.task, "default", "video-key", fixture.boardId,
                new VideoModels.Generate(fixture.revision, new BigDecimal("10"), "SELECTED", false));
        worker.process(videos.job(fixture.task, "default", prep.runId()).orElseThrow());

        assertThat(provider.submitted.get()).isEqualTo(1);
        assertThat(chargeStatus(fixture.task, "video-gen-" + prep.runId() + "-s1")).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT known_cost_cny FROM creator_creation_cost_entry WHERE task_id=? AND charge_key=?",
                BigDecimal.class, fixture.task, "video-gen-" + prep.runId() + "-s1")).isEqualByComparingTo("0");
        assertThat(videos.scene(prep.runId(), "s1").orElseThrow().getErrorCode()).isEqualTo("VIDEO_GENERATION_REJECTED");
        assertThat(runtime.snapshot(fixture.task, "default").budget().paidCallsBlocked()).isFalse();
        assertThat(runs.run(fixture.task, "default", prep.runId()).orElseThrow().status()).isEqualTo("FAILED");
    }

    @Test
    void sentWithoutResultIsNeverResubmittedOnTakeover() {
        var fixture = fixture("AI_VIDEO", List.of(), "海边日落", "NO_DIALOGUE", "NO_DIALOGUE", 2000);
        var prep = service.generate(fixture.task, "default", "video-key", fixture.boardId,
                new VideoModels.Generate(fixture.revision, new BigDecimal("10"), "SELECTED", false));
        var job = videos.job(fixture.task, "default", prep.runId()).orElseThrow();
        var first = runtime.claim(fixture.task, "default", prep.runId(), "lost-worker", 60).orElseThrow();
        String key = "video-gen-" + prep.runId() + "-s1";
        runtime.reserve(fixture.task, "default", prep.runId(), "lost-worker", first.leaseGeneration(), key,
                "VIDEO_GENERATION", new BigDecimal("3.600000"),
                "{\"currency\":\"USD\",\"usdToCny\":\"7.2\",\"usdPerSecond\":\"0.1\"}");
        runtime.markSent(fixture.task, "default", key, "lost-worker", first.leaseGeneration());
        jdbc.update("UPDATE creator_creation_video SET status='SUBMITTING' WHERE run_id=? AND scene_id='s1'", prep.runId());
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE run_id=?", prep.runId());

        worker.process(job);

        assertThat(provider.submitted.get()).isZero();
        assertThat(chargeStatus(fixture.task, key)).isEqualTo("UNKNOWN");
        assertThat(videos.scene(prep.runId(), "s1").orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(videos.scene(prep.runId(), "s1").orElseThrow().getErrorCode()).isEqualTo("VIDEO_RESULT_UNKNOWN");
        assertThat(runs.run(fixture.task, "default", prep.runId()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(count("creator_creation_storyboard", fixture.task)).isEqualTo(1);
    }

    @Test
    void cancelledBeforeClaimNeverCallsProviderAndRowsAreClosed() {
        var fixture = fixture("AI_VIDEO", List.of(), "海边日落", "NO_DIALOGUE", "NO_DIALOGUE", 2000);
        var prep = service.generate(fixture.task, "default", "video-key", fixture.boardId,
                new VideoModels.Generate(fixture.revision, new BigDecimal("10"), "SELECTED", false));
        assertThat(runtime.cancel(fixture.task, "default", prep.runId()).status()).isEqualTo("CANCELLED");
        worker.process(videos.job(fixture.task, "default", prep.runId()).orElseThrow());

        assertThat(provider.submitted.get()).isZero();
        assertThat(videos.terminalWithPending()).anyMatch(ref -> ref.runId().equals(prep.runId()));
        videos.terminalWithPending().forEach(ref -> videoPersistence.closeRows(ref.runId(), "CANCELLED", null));
        assertThat(videos.scene(prep.runId(), "s1").orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThat(videos.terminalWithPending()).noneMatch(ref -> ref.runId().equals(prep.runId()));
    }

    @Test
    void unchangedPromptIsReusedWithoutAnotherProviderCall() {
        var fixture = fixture("AI_VIDEO", List.of(), "海边日落", "NO_DIALOGUE", "NO_DIALOGUE", 2000);
        var first = service.generate(fixture.task, "default", "video-key", fixture.boardId,
                new VideoModels.Generate(fixture.revision, new BigDecimal("10"), "SELECTED", false));
        worker.process(videos.job(fixture.task, "default", first.runId()).orElseThrow());
        String savedBoard = service.view(fixture.task, "default", first.runId()).storyboardResultId();
        assertThat(savedBoard).isNotNull();

        var estimate = service.estimate(fixture.task, "default", savedBoard, "SELECTED", false);
        assertThat(estimate.generateCount()).isZero();
        assertThat(estimate.reuseCount()).isEqualTo(1);
        assertThat(estimate.estimatedMaxCostCny()).isEqualByComparingTo("0");
        var second = service.generate(fixture.task, "default", "video-key-2", savedBoard,
                new VideoModels.Generate(revision(fixture.task), new BigDecimal("0.000001"), "SELECTED", false));
        worker.process(videos.job(fixture.task, "default", second.runId()).orElseThrow());

        assertThat(provider.submitted.get()).isEqualTo(1);
        assertThat(runs.run(fixture.task, "default", second.runId()).orElseThrow().status()).isEqualTo("COMPLETED");
        var reused = videos.scene(second.runId(), "s1").orElseThrow();
        assertThat(reused.getStatus()).isEqualTo("READY");
        assertThat(reused.getVideoAssetId()).isEqualTo(videos.scene(first.runId(), "s1").orElseThrow().getVideoAssetId());
        assertThat(chargeStatus(fixture.task, "video-gen-" + second.runId() + "-s1")).isNull();
    }

    @Test
    void allScopeSwitchesTextScenesAndKeepsPromptAsExplicitFact() throws Exception {
        var fixture = fixture("TITLE_CARD", List.of("一句话"), null, "NO_DIALOGUE", "NO_DIALOGUE", 2000);
        var selected = service.estimate(fixture.task, "default", fixture.boardId, "SELECTED", false);
        assertThat(selected.sceneCount()).isZero();
        assertThat(selected.blockers()).isNotEmpty();

        var estimate = service.estimate(fixture.task, "default", fixture.boardId, "ALL", false);
        assertThat(estimate.sceneCount()).isEqualTo(1);
        assertThat(estimate.switchCount()).isEqualTo(1);
        assertThat(estimate.scenes().getFirst().switchesToAi()).isTrue();
        assertThat(estimate.scenes().getFirst().prompt()).isEqualTo("开场\n一句话");
        var prep = service.generate(fixture.task, "default", "video-key", fixture.boardId,
                new VideoModels.Generate(fixture.revision, new BigDecimal("10"), "ALL", false));
        worker.process(videos.job(fixture.task, "default", prep.runId()).orElseThrow());

        var saved = boards.board(fixture.task, "default", service.view(fixture.task, "default", prep.runId()).storyboardResultId())
                .orElseThrow();
        var scene = codec.candidate(codec.read(saved.documentJson()).path("candidate")).scenes().getFirst();
        assertThat(scene.layout()).isEqualTo("AI_VIDEO");
        assertThat(scene.body()).isEmpty();
        // 正文不再是画面文字，必须显式保存为提示词，否则改用 AI 后摘要会漂移。
        assertThat(scene.visualPrompt()).isEqualTo("开场\n一句话");
        var brief = storyboards.brief(fixture.task, "default", saved.briefId());
        assertThat(compiler.check(saved.storyboardId(), codec.read(brief.documentJson()), new Candidate(List.of(scene)),
                storyboards.frozen(saved)).renderable()).isTrue();
    }

    @Test
    void originalAudioIsNotSilentlyReplacedByAiClip() {
        var fixture = fixture("TITLE_CARD", List.of(), null, "SOURCE", "ORIGINAL", 2000);
        var estimate = service.estimate(fixture.task, "default", fixture.boardId, "ALL", false);
        assertThat(estimate.blockers()).anyMatch(text -> text.contains("原声"));
        assertThat(estimate.scenes().getFirst().action()).isEqualTo("BLOCKED");
        assertThatThrownBy(() -> service.generate(fixture.task, "default", "video-key", fixture.boardId,
                new VideoModels.Generate(fixture.revision, new BigDecimal("10"), "ALL", false)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        assertThat(count("creator_creation_run", fixture.task)).isZero();
    }

    @Test
    void sourceBriefCannotSwitchSilentScenesToAiEither() {
        // SOURCE 简报里图文镜头可以是无对白；切换成 AI 视频后没有可保留的原声，同样必须在付费前阻断。
        var fixture = fixture("TITLE_CARD", List.of("一句话"), null, "SOURCE", "NO_DIALOGUE", 2000);
        var estimate = service.estimate(fixture.task, "default", fixture.boardId, "ALL", false);
        assertThat(estimate.configured()).isTrue();
        assertThat(estimate.blockers()).anyMatch(text -> text.contains("原声"));
        assertThat(estimate.scenes().getFirst().switchesToAi()).isTrue();
        assertThat(estimate.scenes().getFirst().bodyCleared()).isTrue();
        assertThatThrownBy(() -> service.generate(fixture.task, "default", "video-key", fixture.boardId,
                new VideoModels.Generate(fixture.revision, new BigDecimal("10"), "ALL", false)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        assertThat(count("creator_creation_run", fixture.task)).isZero();
    }

    @Test
    void missingProviderCannotStartPaidRun() {
        var fixture = fixture("AI_VIDEO", List.of(), "海边日落", "NO_DIALOGUE", "NO_DIALOGUE", 2000);
        var disabled = new CreationVideoService(boards, storyboards, codec, media, videoPersistence, runtime,
                settings, prices, List.of(), JSON);
        assertThat(disabled.capability().configured()).isFalse();
        assertThatThrownBy(() -> disabled.generate(fixture.task, "default", "video-key", fixture.boardId,
                new VideoModels.Generate(fixture.revision, new BigDecimal("10"), "SELECTED", false)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        assertThat(count("creator_creation_run", fixture.task)).isZero();
        assertThat(count("creator_creation_cost_entry", fixture.task)).isZero();
    }

    @Test
    void runtimeDisabledVideoBlocksNewEstimateAndRun() {
        var fixture = fixture("AI_VIDEO", List.of(), "海边日落", "NO_DIALOGUE", "NO_DIALOGUE", 2000);
        var blocked = new CreationVideoService(boards, storyboards, codec, media, videoPersistence, runtime,
                settings, prices, List.of(provider), JSON,
                CreationCapabilityGate.defaults(false, false), BillingExchangeRate.fixed(new BigDecimal("6.7")));

        var estimate = blocked.estimate(fixture.task, "default", fixture.boardId, "SELECTED", false);

        assertThat(estimate.configured()).isFalse();
        assertThat(estimate.blockers()).anyMatch(text -> text.contains("关闭"));
        assertThatThrownBy(() -> blocked.generate(fixture.task, "default", "video-key", fixture.boardId,
                new VideoModels.Generate(fixture.revision, new BigDecimal("10"), "SELECTED", false)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
        assertThat(count("creator_creation_run", fixture.task)).isZero();
    }

    @Test
    void videoEstimateUsesInjectedLiveRate() {
        var fixture = fixture("AI_VIDEO", List.of(), "海边日落", "NO_DIALOGUE", "NO_DIALOGUE", 2000);
        var cheaper = new CreationVideoService(boards, storyboards, codec, media, videoPersistence, runtime,
                settings, prices, List.of(provider), JSON,
                CreationCapabilityGate.ALL_ENABLED, BillingExchangeRate.fixed(new BigDecimal("6.0")));

        var estimate = cheaper.estimate(fixture.task, "default", fixture.boardId, "SELECTED", false);

        assertThat(estimate.usdToCny()).isEqualByComparingTo("6.0");
        assertThat(estimate.estimatedMaxCostCny()).isEqualByComparingTo("3.000000");
    }

    // ---------------------------------------------------------------- 夹具

    record Fixture(String task, String briefId, String boardId, long revision) { }

    private Fixture fixture(String layout, List<String> body, String prompt, String dialogueMode,
                            String audioMode, int sceneDurationMs) {
        String task = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO creator_task(task_id,user_id,task_name,video_type,status) VALUES (?,?,'隔离视频测试','知识分享','DRAFT')",
                task, "default");
        media.adoptMediaWorkspace(task, "default");
        long revision = runs.workspace(task, "default").orElseThrow().workspaceRevision();
        var brief = storyboards.saveBrief(task, "default", "brief-" + task,
                new BriefInput(revision, "AI 视频准备", "讲清楚一个画面", "第一次做视频的人", sceneDurationMs,
                        dialogueMode, "USER_PROVIDED", List.of(), ""));
        var scene = new Scene("s1", layout, "开场", body, "", sceneDurationMs, null, null, null,
                "CONTAIN", audioMode, null, List.of(), List.of("brief:user"), prompt);
        var board = storyboards.save(task, "default", "board-" + task,
                new Save(revision(task), brief.briefId(), null, new Candidate(List.of(scene))));
        runtime.initialize(task, "default", "budget-" + task, revision(task), new BigDecimal("20"));
        return new Fixture(task, brief.briefId(), board.storyboardId(), revision(task));
    }

    private long revision(String task) { return runs.workspace(task, "default").orElseThrow().workspaceRevision(); }

    @Test void selectedVideoCompletesWhileOtherAiAndVoiceScenesRemainUnprepared(){
        var fixture=fixture("AI_VIDEO",List.of(),"海边日落","NO_DIALOGUE","NO_DIALOGUE",2000);String task=fixture.task;
        var brief=storyboards.saveBrief(task,"default","mixed-brief",new BriefInput(revision(task),"混合场景","只准备指定镜头","新观众",6000,"MIXED","USER_PROVIDED",List.of(),""));
        var first=new Scene("s1","AI_VIDEO","第一幕",List.of(),"",2000,null,null,null,"CONTAIN","NO_DIALOGUE",null,List.of(),List.of("brief:user"),"海边日落");
        var second=new Scene("s2","AI_VIDEO","第二幕",List.of(),"",2000,null,null,null,"CONTAIN","NO_DIALOGUE",null,List.of(),List.of("brief:user"),"山间清晨");
        var third=new Scene("s3","TITLE_CARD","第三幕",List.of(),"等待准备的配音",2000,null,null,null,"CONTAIN","TTS",null,List.of(),List.of("brief:user"));
        var board=storyboards.save(task,"default","mixed-board",new Save(revision(task),brief.briefId(),fixture.boardId,new Candidate(List.of(first,second,third))));
        var prep=service.generate(task,"default","selected-video",board.storyboardId(),new VideoModels.Generate(revision(task),new BigDecimal("10"),"SELECTED",false,List.of("s1")));
        worker.process(videos.job(task,"default",prep.runId()).orElseThrow());var view=service.view(task,"default",prep.runId());
        assertThat(view.status()).isEqualTo("COMPLETED");assertThat(videos.scenes(prep.runId())).hasSize(1);assertThat(provider.submitted.get()).isEqualTo(1);
        var saved=boards.board(task,"default",view.storyboardResultId()).orElseThrow();var result=codec.candidate(codec.read(saved.documentJson()).path("candidate"));
        assertThat(result.scenes().get(0).visualAssetId()).isNotBlank();assertThat(result.scenes().get(1).visualAssetId()).isNull();assertThat(result.scenes().get(2).narrationAssetId()).isNull();
    }

    private String chargeStatus(String task, String key) {
        var values = jdbc.queryForList("SELECT status FROM creator_creation_cost_entry WHERE task_id=? AND charge_key=?",
                String.class, task, key);
        return values.isEmpty() ? null : values.getFirst();
    }

    private int count(String table, String task) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id=?", Integer.class, task);
    }

    @SuppressWarnings("unchecked")
    private static <T> T tx(T target) {
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private static byte[] mp4() {
        return new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0, 'i', 's', 'o', 'm',
                'm', 'p', '4', '2'};
    }

    private static ObjectNode videoProbe(long durationMs) {
        ObjectNode node = JSON.createObjectNode();
        node.putObject("format").put("duration", durationMs / 1000.0);
        node.putArray("streams").addObject().put("codec_type", "video").put("codec_name", "h264")
                .put("width", 1280).put("height", 720).put("duration", durationMs / 1000.0).put("start_time", 0);
        return node;
    }

    /** 本地替身只证明异步协议、费用与持久化，不代表真实视频供应商输出。 */
    static final class StubProvider implements VideoGenerationProvider {
        final AtomicInteger submitted = new AtomicInteger();
        final AtomicInteger queried = new AtomicInteger();
        volatile boolean reject;
        volatile long clipDurationMs = 5000;
        @Override public String provider() { return "compatible"; }
        @Override public String model() { return "test-video-1"; }
        @Override public Capabilities capabilities() {
            return new Capabilities(List.of(5, 10), 1280, 720, new BigDecimal("0.10"));
        }
        @Override public TaskRef submit(Submit request) {
            submitted.incrementAndGet();
            if (reject) throw new VideoRejectedException("HTTP 400");
            return new TaskRef("task-" + submitted.get(), "req-" + submitted.get());
        }
        @Override public QueryResult query(String taskId) {
            queried.incrementAndGet();
            if (queried.get() < 2) return new QueryResult(Status.RUNNING, null, null, null, null, null, null, null);
            return new QueryResult(Status.SUCCEEDED, "https://cdn.example.invalid/" + taskId + ".mp4",
                    5L, clipDurationMs, 1280, 720, null, "req-1");
        }
        @Override public byte[] download(String url, long maxBytes) { return mp4(); }
    }

    /** 用本地目录模拟私有对象存储，内容仍按真实字节读写与摘要核对。 */
    static final class LocalStorage implements ObjectStorageService {
        final Path root;
        LocalStorage() { try { root = Files.createTempDirectory("video-storage-"); } catch (Exception ex) { throw new IllegalStateException(ex); } }
        private Path file(String key) { return root.resolve(key.replace('/', '_')); }
        @Override public MultipartUploadHandle createMultipartUpload(String objectKey, String contentType) { throw new UnsupportedOperationException(); }
        @Override public List<MultipartUploadHandle> listMultipartUploads(String bucketName, String objectKey) { return List.of(); }
        @Override public PresignedUploadPart presignUploadPart(String objectKey, String uploadId, int partNumber, Duration signatureDuration) { throw new UnsupportedOperationException(); }
        @Override public PresignedObjectRead presignGetObject(String bucketName, String objectKey, Duration signatureDuration) { return read(); }
        @Override public PresignedObjectRead presignAsrGetObject(String bucketName, String objectKey, Duration signatureDuration) { return read(); }
        @Override public PresignedObjectRead presignBrowserGetObject(String bucketName, String objectKey, Duration signatureDuration) { return read(); }
        private PresignedObjectRead read() { return new PresignedObjectRead("https://example.invalid/video.mp4", Instant.now().plusSeconds(300)); }
        @Override public void downloadObject(String bucketName, String objectKey, Path targetFile) {
            try { Files.copy(file(objectKey), targetFile); } catch (Exception ex) { throw new MediaStorageException("下载失败", ex); }
        }
        @Override public void putObject(String bucketName, String objectKey, Path sourceFile, String contentType) {
            try { Files.createDirectories(file(objectKey).getParent()); Files.copy(sourceFile, file(objectKey)); }
            catch (Exception ex) { throw new MediaStorageException("上传失败", ex); }
        }
        @Override public void completeMultipartUpload(String objectKey, String uploadId, List<CompletedUploadPart> parts) { throw new UnsupportedOperationException(); }
        @Override public void abortMultipartUpload(String bucketName, String objectKey, String uploadId) { }
        @Override public StoredObjectMetadata headObject(String objectKey) { return headObject("", objectKey); }
        @Override public StoredObjectMetadata headObject(String bucketName, String objectKey) {
            try { return new StoredObjectMetadata(Files.size(file(objectKey)), "video/mp4", "etag"); }
            catch (Exception ex) { throw new MediaStorageException("对象不存在", ex); }
        }
        @Override public void deleteObject(String bucketName, String objectKey) {
            try { Files.deleteIfExists(file(objectKey)); } catch (Exception ignored) { }
        }
    }
}
