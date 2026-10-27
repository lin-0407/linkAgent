package com.link.linkagent.creator.creation.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.asset.mapper.CreationAssetMapper;
import com.link.linkagent.creator.creation.asset.service.AssetMediaPreparer;
import com.link.linkagent.creator.creation.asset.service.CreationAssetPersistence;
import com.link.linkagent.creator.creation.asset.service.CreationAssetService;
import com.link.linkagent.creator.creation.mapper.*;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Run;
import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import com.link.linkagent.creator.creation.billing.BillingExchangeRate;
import com.link.linkagent.creator.creation.service.CreationBillingProperties;
import com.link.linkagent.creator.creation.service.CreationCapabilityGate;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.service.*;
import com.link.linkagent.creator.creation.voice.mapper.CreationVoiceMapper;
import com.link.linkagent.creator.creation.voice.model.VoiceModels;
import com.link.linkagent.creator.creation.voice.service.*;
import com.link.linkagent.creator.creation.voice.provider.SpeechSynthesisProvider;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.config.ObjectStorageProperties;
import com.link.linkagent.creator.media.preflight.provider.SpeechRecognitionProvider;
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

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * 真实 MySQL 验证配音受理、费用、私有音频登记、转写对齐和版本保存。
 * 供应商用本地替身，音频规范化用受控替身，因此这里不证明真实 TTS/ASR 供应商已验收。
 */
@Testcontainers
class CreationVoiceIntegrationTest {
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "voice-isolated-test")
            .withEnv("MYSQL_ROOT_HOST", "%")
            .withEnv("MYSQL_DATABASE", "link_agent")
            .withLabels(Map.of("linkagent.scope", "creation-s4-voice-test"))
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
    static CreationVoiceMapper voices;
    static CreatorTaskMapper tasks;
    static CreationRuntimeService runtime;
    static StoryboardPersistence storyboards;
    static CreationVoicePersistence voicePersistence;
    static StoryboardCodec codec;
    static StoryboardCompiler compiler;
    static ValidatorFactory validators;
    static LocalStorage storage;
    static ObjectStorageProperties storageProperties;
    static Path workRoot;

    CreationVoiceProperties settings;
    CreatorMediaProperties mediaProperties;
    CreationBillingProperties prices;
    StubProvider provider;
    StubAsr asr;
    AssetMediaPreparer preparer;
    CreationVoiceService service;
    CreationVoiceWorker worker;
    StoryboardTextGenerator generator;

    @BeforeAll
    static void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:mysql://" + MYSQL.getHost() + ":"
                + MYSQL.getMappedPort(3306) + "/link_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "root", "voice-isolated-test");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> type : List.of(MediaUploadMapper.class, CreationRuntimeMapper.class, CreationWorkspaceMapper.class,
                CreationMediaMapper.class, CreationPackageMapper.class, CreatorTaskMapper.class,
                CreationAssetMapper.class, StoryboardMapper.class, CreationVoiceMapper.class)) {
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
        voices = session.getMapper(CreationVoiceMapper.class);
        tasks = session.getMapper(CreatorTaskMapper.class);
        runtime = tx(new CreationRuntimeService(media, runs, workspace,
                session.getMapper(CreationMediaMapper.class), session.getMapper(CreationPackageMapper.class), JSON));
        validators = Validation.buildDefaultValidatorFactory();
        codec = new StoryboardCodec(JSON, validators.getValidator());
        compiler = new StoryboardCompiler(JSON);
        storage = new LocalStorage();
        storageProperties = new ObjectStorageProperties();
        storageProperties.setBucket("isolated-voice");
        workRoot = Files.createTempDirectory("voice-work-");
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
        settings = new CreationVoiceProperties();
        settings.setEnabled(true);
        settings.setTranscribeTimeout(Duration.ofSeconds(10));
        settings.setTranscribePollInterval(Duration.ofMillis(10));
        settings.validate();
        mediaProperties = new CreatorMediaProperties();
        // 供应商凭据在部署中配置；测试只验证受理与协议，不代表真实密钥可用。
        mediaProperties.getPreflight().setDashScopeApiKey("test-key");
        prices = new CreationBillingProperties();
        provider = new StubProvider();
        asr = new StubAsr();
        preparer = mock(AssetMediaPreparer.class);
        when(preparer.prepare(any(Path.class), any(Path.class), eq("NARRATION"), any())).thenAnswer(invocation -> {
            Path source = invocation.getArgument(0), output = invocation.getArgument(1);
            long samples = Math.max(1, (Files.size(source) - 44) / 2);
            long durationMs = Math.round(samples * 1000.0 / 24000);
            Path normalized = output.resolve("normalized.wav");
            wav(normalized, 48000, 2, durationMs);
            return new AssetMediaPreparer.Result(probe(24000, 1, durationMs), probe(48000, 2, durationMs),
                    List.of(new AssetMediaPreparer.DerivedFile("NORMALIZED", "normalized.wav", "audio/wav", normalized)),
                    durationMs, null, null, true);
        });
        generator = mock(StoryboardTextGenerator.class);
        when(generator.modelName()).thenReturn("configured-test-text-model");
        storyboards = tx(new StoryboardPersistence(boards, media, tasks, runs, workspace, runtime,
                new CreationAssetService(session.getMapper(CreationAssetMapper.class),
                        tx(new CreationAssetPersistence(session.getMapper(CreationAssetMapper.class))),
                        storageProperties, storage, JSON),
                codec, JSON, prices, generator));
        voicePersistence = tx(new CreationVoicePersistence(voices, runtime, storyboards, media, JSON));
        service = new CreationVoiceService(boards, storyboards, codec, media, voicePersistence, runtime,
                settings, prices, mediaProperties, List.of(provider), JSON);
        var renderer = new FixedProjectRendererProperties();
        renderer.setWorkRoot(workRoot.toString());
        worker = new CreationVoiceWorker(voices, voicePersistence, settings, runtime, prices, boards, storyboards,
                codec, compiler, preparer, storage, storageProperties, renderer, List.of(provider),
                asr, mediaProperties, new VoiceScriptMatcher(), JSON);
    }

    @AfterEach
    void closeWorker() { worker.close(); }

    @Test
    void missingDeploymentKeyIsReportedBeforeAnyRunOrCharge() {
        var fixture = fixture("第一句配音文稿", 3000);
        var withoutKey = new CreatorMediaProperties();
        var serviceWithoutKey = new CreationVoiceService(boards, storyboards, codec, media, voicePersistence, runtime,
                settings, prices, withoutKey, List.of(provider), JSON);
        var estimate = serviceWithoutKey.estimate(fixture.task, "default", fixture.boardId);
        assertThat(estimate.configured()).isFalse();
        assertThat(estimate.blockers()).anyMatch(text -> text.contains("DashScope"));
        assertThatThrownBy(() -> serviceWithoutKey.generate(fixture.task, "default", "voice-key", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("2"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        assertThat(count("creator_creation_run", fixture.task)).isZero();
        assertThat(count("creator_creation_cost_entry", fixture.task)).isZero();
    }

    @Test
    void estimateIsReadOnlyAndGenerateCreatesOneAuthorizedRun() {
        var fixture = fixture("第一句配音文稿", 3000);
        var estimate = service.estimate(fixture.task, "default", fixture.boardId);
        assertThat(estimate.configured()).isTrue();
        assertThat(estimate.blockers()).isEmpty();
        assertThat(estimate.sceneCount()).isEqualTo(1);
        assertThat(estimate.totalCharacters()).isEqualTo("第一句配音文稿".length());
        assertThat(estimate.estimatedMaxAdditionalCostCny()).isPositive();
        assertThat(estimate.basis()).isEqualTo("CONFIGURED_ESTIMATE");
        assertThat(count("creator_creation_run", fixture.task)).isZero();

        var prep = service.generate(fixture.task, "default", "voice-key", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("2")));
        assertThat(prep.runStatus()).isEqualTo("QUEUED");
        assertThat(prep.scenes()).hasSize(1);
        assertThat(prep.scenes().getFirst().status()).isEqualTo("QUEUED");
        assertThat(count("creator_creation_voice_job", fixture.task)).isEqualTo(1);
        assertThat(count("creator_creation_voice", fixture.task)).isEqualTo(1);

        var replay = service.generate(fixture.task, "default", "voice-key", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("2")));
        assertThat(replay.runId()).isEqualTo(prep.runId());
        assertThat(count("creator_creation_run", fixture.task)).isEqualTo(1);
        assertThatThrownBy(() -> service.generate(fixture.task, "default", "voice-key", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("9"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void workerSavesPrivateAudioAndBuildsNewBoardFromRealTranscriptTimestamps() throws Exception {
        var fixture = fixture("第一句配音文稿", 3000);
        var prep = service.generate(fixture.task, "default", "voice-key", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("2")));

        worker.process(voices.job(fixture.task, "default", prep.runId()).orElseThrow());

        var run = runs.run(fixture.task, "default", prep.runId()).orElseThrow();
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(runs.workspace(fixture.task, "default").orElseThrow().activeRunId()).isNull();
        var view = service.view(fixture.task, "default", prep.runId());
        assertThat(view.storyboardId()).isNotNull();
        assertThat(view.requiresUserDecision()).isFalse();
        assertThat(view.scenes().getFirst().status()).isEqualTo("ALIGNED");
        assertThat(view.scenes().getFirst().matchRatio()).isGreaterThan(new BigDecimal("0.99"));
        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(asr.submitted.get()).isEqualTo(1);

        var voice = voices.scene(prep.runId(), "s1").orElseThrow();
        assertThat(voice.getStatus()).isEqualTo("ALIGNED");
        assertThat(voice.getAudioDurationMs()).isEqualTo(1400);
        assertThat(voice.getAudioSha256()).matches("[0-9a-f]{64}");
        assertThat(voice.getTranscriptJson()).contains("\"cues\"").contains("100");
        var assetKind = jdbc.queryForObject("SELECT kind FROM creator_creation_asset WHERE asset_id=?", String.class, voice.getAudioAssetId());
        var assetStatus = jdbc.queryForObject("SELECT status FROM creator_creation_asset WHERE asset_id=?", String.class, voice.getAudioAssetId());
        var assetMetadata = jdbc.queryForObject("SELECT metadata_json FROM creator_creation_asset WHERE asset_id=?", String.class, voice.getAudioAssetId());
        assertThat(assetKind).isEqualTo("NARRATION");
        assertThat(assetStatus).isEqualTo("READY");
        var generated = JSON.readTree(assetMetadata).path("generated");
        assertThat(generated.path("kind").asText()).isEqualTo("TTS");
        assertThat(generated.path("provider").asText()).isEqualTo("dashscope");
        assertThat(generated.path("model").asText()).isEqualTo("cosyvoice-v2");
        assertThat(generated.path("textSha256").asText()).hasSize(64);

        var saved = boards.board(fixture.task, "default", view.storyboardId()).orElseThrow();
        assertThat(saved.parentStoryboardId()).isEqualTo(fixture.boardId);
        var document = codec.read(saved.documentJson());
        assertThat(document.path("subtitleTimingSource").asText()).isEqualTo("ASR_ALIGNED");
        var scene = codec.candidate(document.path("candidate")).scenes().getFirst();
        assertThat(scene.narrationAssetId()).isEqualTo(voice.getAudioAssetId());
        assertThat(scene.audioMode()).isEqualTo("TTS");
        assertThat(scene.cues()).hasSize(1);
        assertThat(scene.cues().getFirst().startMs()).isEqualTo(100);
        assertThat(scene.cues().getFirst().text()).isEqualTo("第一句配音文稿");
        assertThat(saved.assetSnapshotJson()).contains(voice.getAudioAssetId());
        var check = compiler.check(saved.storyboardId(), codec.read(storyboards.brief(fixture.task, "default",
                saved.briefId()).documentJson()), codec.candidate(document.path("candidate")), storyboards.frozen(saved));
        assertThat(check.problems()).isEmpty();
        assertThat(check.renderable()).isTrue();

        assertThat(chargeStatus(fixture.task, "voice-tts-" + prep.runId() + "-s1")).isEqualTo("SETTLED");
        assertThat(chargeStatus(fixture.task, "voice-asr-" + prep.runId() + "-s1")).isEqualTo("SETTLED");
        assertThat(runtime.snapshot(fixture.task, "default").budget().knownCny()).isPositive();

        // 第二次推进不能重复调用供应商或再生成版本。
        worker.process(voices.job(fixture.task, "default", prep.runId()).orElseThrow());
        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(count("creator_creation_storyboard", fixture.task)).isEqualTo(2);
    }

    @Test
    void audioLongerThanSceneStopsBeforeTranscriptionAndReturnsProposal() {
        var fixture = fixture("这是一段明显超过镜头长度的配音文稿内容", 1000);
        var prep = service.generate(fixture.task, "default", "voice-key", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("2")));
        worker.process(voices.job(fixture.task, "default", prep.runId()).orElseThrow());

        var view = service.view(fixture.task, "default", prep.runId());
        assertThat(view.storyboardId()).isNull();
        assertThat(view.requiresUserDecision()).isTrue();
        assertThat(view.scenes().getFirst().status()).isEqualTo("NEEDS_REVIEW");
        assertThat(view.scenes().getFirst().errorCode()).isEqualTo("VOICE_AUDIO_TOO_LONG");
        assertThat(view.proposals()).extracting(VoiceModels.Proposal::code).contains("EXTEND_SCENE");
        assertThat(asr.submitted.get()).isZero();
        assertThat(chargeStatus(fixture.task, "voice-tts-" + prep.runId() + "-s1")).isEqualTo("SETTLED");
        assertThat(chargeStatus(fixture.task, "voice-asr-" + prep.runId() + "-s1")).isNull();
        assertThat(count("creator_creation_storyboard", fixture.task)).isEqualTo(1);
        assertThat(runs.run(fixture.task, "default", prep.runId()).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(runs.workspace(fixture.task, "default").orElseThrow().activeRunId()).isNull();
    }

    @Test
    void mismatchedTranscriptKeepsAudioButNeverFakesSubtitleTiming() {
        asr.transcript = "完全不同的另一段话";
        var fixture = fixture("第一句配音文稿", 3000);
        var prep = service.generate(fixture.task, "default", "voice-key", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("2")));
        worker.process(voices.job(fixture.task, "default", prep.runId()).orElseThrow());

        var view = service.view(fixture.task, "default", prep.runId());
        assertThat(view.storyboardId()).isNull();
        assertThat(view.scenes().getFirst().status()).isEqualTo("NEEDS_REVIEW");
        assertThat(view.scenes().getFirst().errorCode()).isEqualTo("VOICE_SCRIPT_MISMATCH");
        assertThat(view.scenes().getFirst().audioAssetId()).isNotNull();
        assertThat(view.proposals()).extracting(VoiceModels.Proposal::code).contains("VOICE_SCRIPT_CONFIRM");
        assertThat(count("creator_creation_storyboard", fixture.task)).isEqualTo(1);
        assertThat(chargeStatus(fixture.task, "voice-asr-" + prep.runId() + "-s1")).isEqualTo("SETTLED");
    }

    @Test
    void restartedWorkerResumesTranscriptionWithoutSubmittingAgain() throws Exception {
        var fixture = fixture("第一句配音文稿", 3000);
        var prep = service.generate(fixture.task, "default", "voice-key", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("2")));
        var job = voices.job(fixture.task, "default", prep.runId()).orElseThrow();
        var first = runtime.claim(fixture.task, "default", prep.runId(), "lost-worker", 60).orElseThrow();

        // 模拟“合成已落库、转写已提交、进程随后崩溃”：只恢复查询，不得再次提交。
        String assetId = UUID.randomUUID().toString();
        Path normalized = workRoot.resolve("resume-" + assetId + ".wav");
        wav(normalized, 48000, 2, 1400);
        String objectKey = "users/default/tasks/" + fixture.task + "/assets/" + assetId + "/prepared/voice/normalized.wav";
        storage.putObject("isolated-voice", objectKey, normalized, "audio/wav");
        String sha = digest(normalized);
        ObjectNode metadata = JSON.createObjectNode();
        metadata.set("original", probe(24000, 1, 1400));
        metadata.set("normalized", probe(48000, 2, 1400));
        metadata.putObject("generated").put("kind", "TTS").put("provider", "dashscope")
                .put("textSha256", StoryboardCodec.digest("第一句配音文稿"));
        voices.insertGeneratedAsset(assetId, fixture.task, "default", "voice-" + prep.runId() + "-s1",
                "voice-s1.wav", Files.size(normalized), sha, "isolated-voice", objectKey,
                JSON.writeValueAsString(metadata), JSON.writeValueAsString(List.of(
                        new com.link.linkagent.creator.creation.asset.model.AssetRequests.StoredFile(
                                "NORMALIZED", objectKey, "audio/wav", Files.size(normalized), sha))));
        jdbc.update("""
                UPDATE creator_creation_voice SET status='TRANSCRIBING',provider_task_id='task-existing',
                  audio_asset_id=?,audio_sha256=?,audio_bytes=?,audio_duration_ms=1400,provider='dashscope',
                  model='cosyvoice-v2',voice_id='longxiaochun_v2' WHERE run_id=? AND scene_id='s1'
                """, assetId, sha, Files.size(normalized), prep.runId());
        String key = "voice-asr-" + prep.runId() + "-s1";
        runtime.reserve(fixture.task, "default", prep.runId(), "lost-worker", first.leaseGeneration(), key,
                "VOICE_ASR", new BigDecimal("0.001000"),
                "{\"currency\":\"USD\",\"usdToCny\":\"7.2\",\"usdPerSecond\":\"0.000035\",\"usdUpperBound\":\"0.001\"}");
        runtime.markSent(fixture.task, "default", key, "lost-worker", first.leaseGeneration());
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE run_id=?",
                prep.runId());

        worker.process(job);

        assertThat(asr.submitted.get()).isZero();
        assertThat(asr.queries.get()).isPositive();
        assertThat(voices.scene(prep.runId(), "s1").orElseThrow().getStatus()).isEqualTo("ALIGNED");
        assertThat(chargeStatus(fixture.task, key)).isEqualTo("SETTLED");
        assertThat(runs.run(fixture.task, "default", prep.runId()).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(service.view(fixture.task, "default", prep.runId()).storyboardId()).isNotNull();
    }

    @Test
    void explicitProviderRejectionSettlesZeroAndDoesNotBlockTheTask() {
        provider.reject = true;
        var fixture = fixture("第一句配音文稿", 3000);
        var prep = service.generate(fixture.task, "default", "voice-key", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("2")));
        worker.process(voices.job(fixture.task, "default", prep.runId()).orElseThrow());

        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(chargeStatus(fixture.task, "voice-tts-" + prep.runId() + "-s1")).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT known_cost_cny FROM creator_creation_cost_entry WHERE task_id=? AND charge_key=?",
                BigDecimal.class, fixture.task, "voice-tts-" + prep.runId() + "-s1")).isEqualByComparingTo("0");
        assertThat(voices.scene(prep.runId(), "s1").orElseThrow().getErrorCode()).isEqualTo("VOICE_TTS_REJECTED");
        assertThat(runtime.snapshot(fixture.task, "default").budget().paidCallsBlocked()).isFalse();
        assertThat(runs.run(fixture.task, "default", prep.runId()).orElseThrow().status()).isEqualTo("FAILED");
    }

    @Test
    void interruptedSynthesisWithSentChargeIsMarkedUnknownWithoutResending() {
        var fixture = fixture("第一句配音文稿", 3000);
        var prep = service.generate(fixture.task, "default", "voice-key", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("2")));
        var job = voices.job(fixture.task, "default", prep.runId()).orElseThrow();
        var first = runtime.claim(fixture.task, "default", prep.runId(), "lost-worker", 60).orElseThrow();
        String key = "voice-tts-" + prep.runId() + "-s1";
        runtime.reserve(fixture.task, "default", prep.runId(), "lost-worker", first.leaseGeneration(), key,
                "VOICE_TTS", new BigDecimal("0.010000"), "{\"currency\":\"USD\"}");
        runtime.markSent(fixture.task, "default", key, "lost-worker", first.leaseGeneration());
        jdbc.update("UPDATE creator_creation_voice SET status='SYNTHESIZING' WHERE run_id=? AND scene_id='s1'", prep.runId());
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE run_id=?", prep.runId());

        worker.process(job);

        assertThat(provider.calls.get()).isZero();
        assertThat(chargeStatus(fixture.task, key)).isEqualTo("UNKNOWN");
        assertThat(voices.scene(prep.runId(), "s1").orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(voices.scene(prep.runId(), "s1").orElseThrow().getErrorCode()).isEqualTo("VOICE_TTS_RESULT_UNKNOWN");
        assertThat(runs.run(fixture.task, "default", prep.runId()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(count("creator_creation_storyboard", fixture.task)).isEqualTo(1);
    }

    @Test
    void interruptedSynthesisWithoutSentChargeIsRequeuedAndCompleted() {
        var fixture = fixture("第一句配音文稿", 3000);
        var prep = service.generate(fixture.task, "default", "voice-key", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("2")));
        var job = voices.job(fixture.task, "default", prep.runId()).orElseThrow();
        var first = runtime.claim(fixture.task, "default", prep.runId(), "lost-worker", 60).orElseThrow();
        jdbc.update("UPDATE creator_creation_voice SET status='SYNTHESIZING' WHERE run_id=? AND scene_id='s1'", prep.runId());
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE run_id=?", prep.runId());
        assertThat(first.leaseGeneration()).isPositive();

        worker.process(job);

        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(voices.scene(prep.runId(), "s1").orElseThrow().getStatus()).isEqualTo("ALIGNED");
        assertThat(runs.run(fixture.task, "default", prep.runId()).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(chargeStatus(fixture.task, "voice-tts-" + prep.runId() + "-s1")).isEqualTo("SETTLED");
    }

    @Test
    void sentCallWithoutResultIsNeverResentOnTakeover() {
        var fixture = fixture("第一句配音文稿", 3000);
        var prep = service.generate(fixture.task, "default", "voice-key", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("2")));
        var job = voices.job(fixture.task, "default", prep.runId()).orElseThrow();
        var first = runtime.claim(fixture.task, "default", prep.runId(), "lost-worker", 60).orElseThrow();
        String key = "voice-tts-" + prep.runId() + "-s1";
        runtime.reserve(fixture.task, "default", prep.runId(), "lost-worker", first.leaseGeneration(), key,
                "VOICE_TTS", new BigDecimal("0.010000"), "{\"currency\":\"USD\"}");
        runtime.markSent(fixture.task, "default", key, "lost-worker", first.leaseGeneration());
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE run_id=?",
                prep.runId());

        worker.process(job);

        assertThat(provider.calls.get()).isZero();
        assertThat(chargeStatus(fixture.task, key)).isEqualTo("UNKNOWN");
        var run = runs.run(fixture.task, "default", prep.runId()).orElseThrow();
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(voices.scene(prep.runId(), "s1").orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(count("creator_creation_asset", fixture.task)).isZero();
    }

    @Test
    void cancelledBeforeClaimNeverCallsProviderAndRowsAreClosed() {
        var fixture = fixture("第一句配音文稿", 3000);
        var prep = service.generate(fixture.task, "default", "voice-key", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("2")));
        assertThat(runtime.cancel(fixture.task, "default", prep.runId()).status()).isEqualTo("CANCELLED");
        worker.process(voices.job(fixture.task, "default", prep.runId()).orElseThrow());
        assertThat(provider.calls.get()).isZero();
        assertThat(voices.terminalWithPending()).anyMatch(ref -> ref.runId().equals(prep.runId()));
        voices.terminalWithPending().forEach(ref -> voicePersistence.closeRows(ref.runId(), "CANCELLED", null));
        assertThat(voices.scene(prep.runId(), "s1").orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThat(voices.terminalWithPending()).noneMatch(ref -> ref.runId().equals(prep.runId()));
    }

    @Test
    void estimateExplainsMissingProviderWithoutCreatingAnything() {
        var fixture = fixture("第一句配音文稿", 3000);
        var disabled = new CreationVoiceService(boards, storyboards, codec, media, voicePersistence, runtime,
                settings, prices, mediaProperties, List.of(), JSON);
        var estimate = disabled.estimate(fixture.task, "default", fixture.boardId);
        assertThat(estimate.configured()).isFalse();
        assertThat(estimate.blockers()).isNotEmpty();
        assertThatThrownBy(() -> disabled.generate(fixture.task, "default", "voice-key", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("2"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
        assertThat(count("creator_creation_run", fixture.task)).isZero();
    }

    @Test
    void runtimeDisabledVoiceBlocksNewEstimateAndRun() {
        var fixture = fixture("第一句配音文稿", 3000);
        var blocked = new CreationVoiceService(boards, storyboards, codec, media, voicePersistence, runtime,
                settings, prices, mediaProperties, List.of(provider), JSON,
                CreationCapabilityGate.defaults(false, false), BillingExchangeRate.fixed(new BigDecimal("6.7")));

        var estimate = blocked.estimate(fixture.task, "default", fixture.boardId);

        assertThat(estimate.configured()).isFalse();
        assertThat(estimate.blockers()).anyMatch(text -> text.contains("关闭"));
        assertThatThrownBy(() -> blocked.generate(fixture.task, "default", "voice-key", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("2"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
        assertThat(count("creator_creation_run", fixture.task)).isZero();
    }

    @Test
    void estimateAndAcceptanceUseInjectedLiveRateInPriceSnapshot() throws Exception {
        var fixture = fixture("第一句配音文稿", 3000);
        var cheaper = new CreationVoiceService(boards, storyboards, codec, media, voicePersistence, runtime,
                settings, prices, mediaProperties, List.of(provider), JSON,
                CreationCapabilityGate.ALL_ENABLED, BillingExchangeRate.fixed(new BigDecimal("6.0")));

        var estimate = cheaper.estimate(fixture.task, "default", fixture.boardId);
        var prep = cheaper.generate(fixture.task, "default", "voice-live-rate", fixture.boardId,
                new VoiceModels.Generate(fixture.revision, new BigDecimal("2")));

        assertThat(estimate.usdToCny()).isEqualByComparingTo("6.0");
        String request = jdbc.queryForObject("SELECT request_json FROM creator_creation_run WHERE run_id=?",
                String.class, prep.runId());
        assertThat(JSON.readTree(request).path("price").path("usdToCny").decimalValue())
                .isEqualByComparingTo("6.0");
    }

    // ---------------------------------------------------------------- 夹具

    record Fixture(String task, String briefId, String boardId, long revision) { }

    private Fixture fixture(String text, int sceneDurationMs) {
        String task = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO creator_task(task_id,user_id,task_name,video_type,status) VALUES (?,?,'隔离配音测试','知识分享','DRAFT')",
                task, "default");
        media.adoptMediaWorkspace(task, "default");
        long revision = runs.workspace(task, "default").orElseThrow().workspaceRevision();
        var brief = storyboards.saveBrief(task, "default", "brief-" + task,
                new BriefInput(revision, "配音准备", "讲清楚一段话", "第一次做视频的人", sceneDurationMs,
                        "TTS", "USER_PROVIDED", List.of(), ""));
        var scene = new Scene("s1", "TITLE_CARD", "开场", List.of("一句话"), text, sceneDurationMs,
                null, null, null, "CONTAIN", "TTS", null, List.of(), List.of("brief:user"));
        // 简报保存会推进工作区修订，故事板必须使用当前修订，否则会被并发门禁拒绝。
        var board = storyboards.save(task, "default", "board-" + task,
                new Save(revision(task), brief.briefId(), null, new Candidate(List.of(scene))));
        runtime.initialize(task, "default", "budget-" + task, revision(task), new BigDecimal("20"));
        return new Fixture(task, brief.briefId(), board.storyboardId(), revision(task));
    }

    private long revision(String task) { return runs.workspace(task, "default").orElseThrow().workspaceRevision(); }

    @Test void selectedVoiceCompletesWhileUnselectedVoiceAndAiClipRemainUnprepared(){
        var fixture=fixture("第一句配音文稿",2000);String task=fixture.task;
        var brief=storyboards.saveBrief(task,"default","mixed-brief",new BriefInput(revision(task),"混合场景","只准备指定配音","新观众",6000,"MIXED","USER_PROVIDED",List.of(),""));
        var first=new Scene("s1","TITLE_CARD","第一幕",List.of(),"第一句配音文稿",2000,null,null,null,"CONTAIN","TTS",null,List.of(),List.of("brief:user"));
        var second=new Scene("s2","TITLE_CARD","第二幕",List.of(),"第二句待准备",2000,null,null,null,"CONTAIN","TTS",null,List.of(),List.of("brief:user"));
        var third=new Scene("s3","AI_VIDEO","第三幕",List.of(),"",2000,null,null,null,"CONTAIN","NO_DIALOGUE",null,List.of(),List.of("brief:user"),"海边日落");
        var board=storyboards.save(task,"default","mixed-board",new Save(revision(task),brief.briefId(),fixture.boardId,new Candidate(List.of(first,second,third))));
        var prep=service.generate(task,"default","selected-voice",board.storyboardId(),new VoiceModels.Generate(revision(task),new BigDecimal("2"),List.of("s1")));
        worker.process(voices.job(task,"default",prep.runId()).orElseThrow());var view=service.view(task,"default",prep.runId());
        assertThat(view.runStatus()).isEqualTo("COMPLETED");assertThat(view.scenes()).hasSize(1);assertThat(provider.calls.get()).isEqualTo(1);
        var saved=boards.board(task,"default",view.storyboardId()).orElseThrow();var result=codec.candidate(codec.read(saved.documentJson()).path("candidate"));
        assertThat(result.scenes().get(0).narrationAssetId()).isNotBlank();assertThat(result.scenes().get(1).narrationAssetId()).isNull();assertThat(result.scenes().get(2).visualAssetId()).isNull();
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

    private static String digest(Path file) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static ObjectNode probe(int rate, int channels, long durationMs) {
        ObjectNode node = JSON.createObjectNode();
        node.putObject("format").put("duration", durationMs / 1000.0);
        node.putArray("streams").addObject().put("codec_type", "audio").put("codec_name", "pcm_s16le")
                .put("sample_rate", rate).put("channels", channels).put("duration", durationMs / 1000.0)
                .put("start_time", 0);
        return node;
    }

    static void wav(Path target, int rate, int channels, long durationMs) throws Exception {
        int frames = (int) Math.max(1, rate * durationMs / 1000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(36 + frames * channels * 2).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16)
                .put("data".getBytes()).putInt(frames * channels * 2);
        bytes.write(header.array());
        bytes.write(new byte[frames * channels * 2]);
        Files.write(target, bytes.toByteArray());
    }

    /** 本地替身只证明协议与持久化，不代表真实供应商输出。 */
    static final class StubProvider implements SpeechSynthesisProvider {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean reject;
        @Override public String provider() { return "dashscope"; }
        @Override public String model() { return "cosyvoice-v2"; }
        @Override public String voice() { return "longxiaochun_v2"; }
        @Override public Audio synthesize(String text) {
            calls.incrementAndGet();
            if (reject) throw new com.link.linkagent.creator.creation.voice.provider.DashScopeCosyVoiceProvider.SynthesisRejectedException("HTTP 400");
            long duration = Math.max(300, text.codePointCount(0, text.length()) * 200L);
            try {
                Path file = Files.createTempFile("tts-", ".wav");
                wav(file, 24000, 1, duration);
                byte[] bytes = Files.readAllBytes(file);
                Files.deleteIfExists(file);
                return new Audio(bytes, "audio/wav", "req-" + calls.get(), (long) text.length());
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        }
    }

    static final class StubAsr implements SpeechRecognitionProvider {
        final AtomicInteger submitted = new AtomicInteger();
        final AtomicInteger queries = new AtomicInteger();
        volatile String transcript;
        @Override public String submit(String audioUrl) {
            submitted.incrementAndGet();
            return "task-" + submitted.get();
        }
        @Override public QueryResult query(String providerTaskId) {
            queries.incrementAndGet();
            return new QueryResult(Status.SUCCEEDED, "https://example.invalid/result.json", 2L, null);
        }
        @Override public TranscriptionResult loadResult(String transcriptionUrl) {
            String text = transcript == null ? "第一句配音文稿" : transcript;
            return new TranscriptionResult(List.of(new Segment(100, 1000, text, 0.98, null, "zh")));
        }
    }

    /** 用本地目录模拟私有对象存储，内容仍按真实字节读写与摘要核对。 */
    static final class LocalStorage implements ObjectStorageService {
        final Path root;
        LocalStorage() { try { root = Files.createTempDirectory("voice-storage-"); } catch (Exception ex) { throw new IllegalStateException(ex); } }
        private Path file(String key) { return root.resolve(key.replace('/', '_')); }
        @Override public MultipartUploadHandle createMultipartUpload(String objectKey, String contentType) { throw new UnsupportedOperationException(); }
        @Override public List<MultipartUploadHandle> listMultipartUploads(String bucketName, String objectKey) { return List.of(); }
        @Override public PresignedUploadPart presignUploadPart(String objectKey, String uploadId, int partNumber, Duration signatureDuration) { throw new UnsupportedOperationException(); }
        @Override public PresignedObjectRead presignGetObject(String bucketName, String objectKey, Duration signatureDuration) { return read(); }
        @Override public PresignedObjectRead presignAsrGetObject(String bucketName, String objectKey, Duration signatureDuration) { return read(); }
        @Override public PresignedObjectRead presignBrowserGetObject(String bucketName, String objectKey, Duration signatureDuration) { return read(); }
        private PresignedObjectRead read() { return new PresignedObjectRead("https://example.invalid/audio.wav", Instant.now().plusSeconds(300)); }
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
            try { return new StoredObjectMetadata(Files.size(file(objectKey)), "audio/wav", "etag"); }
            catch (Exception ex) { throw new MediaStorageException("对象不存在", ex); }
        }
        @Override public void deleteObject(String bucketName, String objectKey) {
            try { Files.deleteIfExists(file(objectKey)); } catch (Exception ignored) { }
        }
    }
}
