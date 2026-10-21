package com.link.linkagent.creator.creation.storyboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.asset.mapper.CreationAssetMapper;
import com.link.linkagent.creator.creation.asset.model.AssetRequests.StoredFile;
import com.link.linkagent.creator.creation.asset.service.CreationAssetPersistence;
import com.link.linkagent.creator.creation.asset.service.CreationAssetService;
import com.link.linkagent.creator.creation.mapper.*;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Run;
import com.link.linkagent.creator.creation.service.CreationBillingProperties;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardPersistence;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardTextGenerator;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardWorker;
import com.link.linkagent.creator.media.config.ObjectStorageProperties;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mockito.ArgumentCaptor;
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
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** 真实 MySQL 验证简报、素材引用和运行事务；素材字节及模型候选均为受控夹具，不代表实际上传或供应商生成。 */
@Testcontainers
class StoryboardPersistenceIntegrationTest {
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "storyboard-isolated-test")
            .withEnv("MYSQL_ROOT_HOST", "%")
            .withEnv("MYSQL_DATABASE", "link_agent")
            .withLabels(Map.of("linkagent.scope", "creation-s4-storyboard-test"))
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
    static StoryboardMapper mapper;
    static CreatorTaskMapper tasks;
    static CreationRuntimeService runtime;
    static CreationAssetService assets;
    static StoryboardCodec codec;
    static ValidatorFactory validators;
    StoryboardTextGenerator generator;
    StoryboardPersistence persistence;
    CreationBillingProperties prices;

    @BeforeAll
    static void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:mysql://" + MYSQL.getHost() + ":"
                + MYSQL.getMappedPort(3306) + "/link_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "root", "storyboard-isolated-test");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> type : List.of(MediaUploadMapper.class, CreationRuntimeMapper.class,
                CreationWorkspaceMapper.class, CreationMediaMapper.class, CreationPackageMapper.class,
                CreatorTaskMapper.class, CreationAssetMapper.class, StoryboardMapper.class)) {
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
        mapper = session.getMapper(StoryboardMapper.class);
        tasks = session.getMapper(CreatorTaskMapper.class);
        runtime = tx(new CreationRuntimeService(media, runs, workspace,
                session.getMapper(CreationMediaMapper.class), session.getMapper(CreationPackageMapper.class), JSON));
        var assetMapper = session.getMapper(CreationAssetMapper.class);
        assets = new CreationAssetService(assetMapper, tx(new CreationAssetPersistence(assetMapper)),
                new ObjectStorageProperties(), mock(ObjectStorageService.class), JSON);
        validators = Validation.buildDefaultValidatorFactory();
        codec = new StoryboardCodec(JSON, validators.getValidator());
    }

    @AfterAll
    static void closeValidationFactory() {
        if (validators != null) validators.close();
    }

    @BeforeEach
    void boundaries() {
        generator = mock(StoryboardTextGenerator.class);
        when(generator.modelName()).thenReturn("configured-test-text-model");
        prices = new CreationBillingProperties();
        persistence = service(mapper);
    }

    @Test
    void briefVersionsFreezeRealMaterialsAndSameKeyReplaysOriginalWithoutBumping() {
        String task = task("default");
        String sourceId = material(task, "最初由用户输入的事实");
        var input = brief(revision(task), List.of());
        var first = persistence.saveBrief(task, "default", "brief-first", input);
        assertThat(first.revision()).isEqualTo(1);
        assertThat(codec.read(first.sourceJson()).path("materials").get(0).path("sourceId").asText()).isEqualTo(sourceId);
        assertThat(codec.read(first.sourceJson()).path("materials").get(0).path("sha256").asText())
                .isEqualTo(StoryboardCodec.digest("最初由用户输入的事实"));
        assertThat(codec.read(first.documentJson()).has("expectedWorkspaceRevision")).isFalse();
        long afterFirst = revision(task);
        jdbc.update("UPDATE creator_material SET content='后来修改的材料' WHERE task_id=?", task);
        var replay = persistence.saveBrief(task, "default", "brief-first", input);
        assertThat(replay.briefId()).isEqualTo(first.briefId());
        assertThat(replay.sourceJson()).isEqualTo(first.sourceJson());
        assertThat(revision(task)).isEqualTo(afterFirst);
        var second = persistence.saveBrief(task, "default", "brief-second", brief(afterFirst, List.of()));
        assertThat(second.revision()).isEqualTo(2);
        assertThat(codec.read(second.sourceJson()).path("materials").get(0).path("content").asText()).isEqualTo("后来修改的材料");
        assertThat(mapper.brief(task, "default", first.briefId()).orElseThrow().sourceJson()).isEqualTo(first.sourceJson());
        assertThat(count("creator_creation_brief", task)).isEqualTo(2);
        verify(generator, never()).generate(anyString(), anyString(), anyInt());
    }

    @Test
    void sameBriefKeyWithDifferentContentIsConflictAndOriginalSurvives() {
        String task = task("default");
        var input = brief(revision(task), List.of());
        var first = persistence.saveBrief(task, "default", "same-key", input);
        var changed = new BriefInput(input.expectedWorkspaceRevision(), "另一个主题", input.coreMessage(),
                input.audience(), input.targetDurationMs(), input.dialogueMode(), input.contentKind(), input.assetIds(), input.constraints());
        conflict(() -> persistence.saveBrief(task, "default", "same-key", changed));
        assertThat(count("creator_creation_brief", task)).isEqualTo(1);
        assertThat(mapper.brief(task, "default", first.briefId()).orElseThrow().documentJson()).isEqualTo(first.documentJson());
        assertThat(revision(task)).isEqualTo(1);
    }

    @Test
    void concurrentBriefReplayCreatesOnlyOneImmutableVersion() throws Exception {
        String task = task("default");
        var input = brief(revision(task), List.of());
        try (var executor = Executors.newFixedThreadPool(3)) {
            var futures = executor.<Brief>invokeAll(List.of(
                    () -> persistence.saveBrief(task, "default", "concurrent", input),
                    () -> persistence.saveBrief(task, "default", "concurrent", input),
                    () -> persistence.saveBrief(task, "default", "concurrent", input)));
            String first = futures.getFirst().get(10, TimeUnit.SECONDS).briefId();
            for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS).briefId()).isEqualTo(first);
        }
        assertThat(count("creator_creation_brief", task)).isEqualTo(1);
        assertThat(revision(task)).isEqualTo(1);
    }

    @Test
    void staleWorkspaceCannotCreateAnyBriefOrRun() {
        String task = task("default");
        var first = persistence.saveBrief(task, "default", "first", brief(0, List.of()));
        conflict(() -> persistence.saveBrief(task, "default", "stale", brief(0, List.of())));
        initialize(task);
        conflict(() -> persistence.generate(task, "default", "stale", new Generate(0L, first.briefId(), cny("1"))));
        assertThat(count("creator_creation_brief", task)).isEqualTo(1);
        assertThat(count("creator_creation_run", task)).isZero();
        assertThat(count("creator_creation_approval", task)).isZero();
    }

    @Test
    void activePreparationBlocksBriefAndManualBoardUntilCancelled() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2")));
        long activeRevision = revision(task);
        conflict(() -> persistence.saveBrief(task, "default", "new-brief", brief(activeRevision, List.of())));
        conflict(() -> persistence.save(task, "default", "manual", new Save(activeRevision, brief.briefId(), null, candidate(null, List.of("brief:user"), List.of()))));
        runtime.cancel(task, "default", job.runId());
        var accepted = persistence.saveBrief(task, "default", "new-brief", brief(revision(task), List.of()));
        assertThat(accepted.revision()).isEqualTo(2);
        assertThat(count("creator_creation_storyboard", task)).isZero();
    }

    @Test
    void anyBindingFactBlocksNewBriefBoardAndPreparation() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        // 即使还在核验，也已经进入发布后链路，不能从故事板入口绕回付费制作。
        jdbc.update("INSERT INTO creator_task_video_binding(binding_id,task_id,user_id,bvid,binding_status) VALUES (?,?,'default','BV1TEST000001','WAITING_VERIFY')", UUID.randomUUID().toString(), task);
        conflict(() -> persistence.saveBrief(task, "default", "later", brief(revision(task), List.of())));
        conflict(() -> persistence.save(task, "default", "manual", new Save(revision(task), brief.briefId(), null, candidate(null, List.of("brief:user"), List.of()))));
        conflict(() -> persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2"))));
        assertThat(count("creator_creation_approval", task)).isZero();
        assertThat(count("creator_creation_run", task)).isZero();
        assertThat(count("creator_creation_storyboard", task)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPLOADING", "PREPARING", "FAILED", "CANCELLED"})
    void onlyReadyAssetCanEnterBrief(String state) {
        String task = task("default");
        String asset = asset(task, "default", "same-name.png", "a");
        jdbc.update("UPDATE creator_creation_asset SET status=? WHERE asset_id=?", state, asset);
        conflict(() -> persistence.saveBrief(task, "default", "brief", brief(0, List.of(asset))));
        assertThat(count("creator_creation_brief", task)).isZero();
        assertThat(revision(task)).isZero();
    }

    @Test
    void foreignAssetMissingNormalizedFileAndDuplicateSelectionAreRejected() {
        String task = task("default");
        String other = task("other");
        String foreign = asset(other, "other", "same-name.png", "a");
        rejected(() -> persistence.saveBrief(task, "default", "foreign", brief(0, List.of(foreign))), 404);
        String sameOwnerOtherTask = task("default");
        String outsideTask = asset(sameOwnerOtherTask, "default", "same-name.png", "a");
        rejected(() -> persistence.saveBrief(task, "default", "foreign-task", brief(0, List.of(outsideTask))), 404);
        String own = asset(task, "default", "same-name.png", "b");
        conflict(() -> persistence.saveBrief(task, "default", "duplicate", brief(0, List.of(own, own))));
        jdbc.update("UPDATE creator_creation_asset SET objects_json='[]' WHERE asset_id=?", own);
        conflict(() -> persistence.saveBrief(task, "default", "missing-work-file", brief(0, List.of(own))));
        assertThat(count("creator_creation_brief", task)).isZero();
    }

    @Test
    void sameFilenameDoesNotRetargetFrozenAssetAndReferencesRemainQueryable() {
        String task = task("default");
        String first = asset(task, "default", "same-name.png", "a");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of(first)));
        var board = persistence.save(task, "default", "manual", new Save(revision(task), brief.briefId(), null, candidate(first, List.of("brief:user"), List.of())));
        String replacement = asset(task, "default", "same-name.png", "b");
        assertThat(replacement).isNotEqualTo(first);
        var frozen = persistence.frozen(mapper.board(task, "default", board.storyboardId()).orElseThrow());
        assertThat(frozen).hasSize(1);
        assertThat(frozen.getFirst().assetId()).isEqualTo(first);
        assertThat(frozen.getFirst().sha256()).isEqualTo("a".repeat(64));
        assertThat(frozen.getFirst().objectKey()).contains(first).doesNotContain(replacement);
        assertThat(frozen.getFirst().metadata().path("width").asInt()).isEqualTo(640);
        assertThat(mapper.references(task, "default", first, 0, 20))
                .extracting(StoryboardMapper.Reference::storyboardId).containsExactly(board.storyboardId());
        assertThat(mapper.references(task, "default", replacement, 0, 20)).isEmpty();
        assertThat(mapper.references(task, "other", first, 0, 20)).isEmpty();
        assertThat(board.assetSnapshotJson()).doesNotContain("https://", "signature", "accessKey");
    }

    @Test
    void manualEditsCreateChildVersionAndCannotBorrowAnotherTasksParent() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        var firstInput = new Save(revision(task), brief.briefId(), null, candidate(null, List.of("brief:user"), List.of()));
        var first = persistence.save(task, "default", "first", firstInput);
        long afterFirst = revision(task);
        assertThat(persistence.save(task, "default", "first", firstInput).storyboardId()).isEqualTo(first.storyboardId());
        assertThat(revision(task)).isEqualTo(afterFirst);
        var child = persistence.save(task, "default", "second", new Save(afterFirst, brief.briefId(), first.storyboardId(), candidate(null, List.of("brief:user"), List.of())));
        assertThat(child.parentStoryboardId()).isEqualTo(first.storyboardId());
        assertThat(child.revision()).isEqualTo(2);
        assertThat(child.storyboardId()).isNotEqualTo(first.storyboardId());
        assertThat(mapper.board(task, "default", first.storyboardId()).orElseThrow().documentHash()).isEqualTo(first.documentHash());
        String another = task("default");
        var anotherBrief = persistence.saveBrief(another, "default", "brief", brief(0, List.of()));
        conflict(() -> persistence.save(another, "default", "foreign-parent", new Save(revision(another), anotherBrief.briefId(), first.storyboardId(), candidate(null, List.of("brief:user"), List.of()))));
        assertThat(count("creator_creation_storyboard", another)).isZero();
    }

    @Test
    void updatedBriefRejectsOldBriefBoardAndPreparationWithoutChangingHistory() {
        String task = task("default");
        var first = persistence.saveBrief(task, "default", "first", brief(0, List.of()));
        persistence.saveBrief(task, "default", "second", brief(revision(task), List.of()));
        initialize(task);
        conflict(() -> persistence.save(task, "default", "stale", new Save(revision(task), first.briefId(), null, candidate(null, List.of("brief:user"), List.of()))));
        conflict(() -> persistence.generate(task, "default", "stale", new Generate(revision(task), first.briefId(), cny("1"))));
        assertThat(count("creator_creation_storyboard", task)).isZero();
        assertThat(count("creator_creation_run", task)).isZero();
    }

    @Test
    void factsUseRealMaterialSourceIdsAndInventedSourceIsRejected() {
        String task = task("default");
        String sourceId = material(task, "用户确认的文稿");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        conflict(() -> persistence.save(task, "default", "invented", new Save(revision(task), brief.briefId(), null, candidate(null, List.of("material:invented"), List.of()))));
        conflict(() -> persistence.save(task, "default", "missing", new Save(revision(task), brief.briefId(), null, candidate(null, List.of(), List.of()))));
        var board = persistence.save(task, "default", "real", new Save(revision(task), brief.briefId(), null, candidate(null, List.of(sourceId), List.of())));
        assertThat(codec.read(board.documentJson()).path("candidate").path("scenes").get(0).path("sourceRefs").get(0).asText()).isEqualTo(sourceId);
        assertThat(count("creator_creation_storyboard", task)).isEqualTo(1);
    }

    @Test
    void modelCannotInventSubtitleTimesButUserProvidedCuesCanBeSaved() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        var supplied = candidate(null, List.of("brief:user"), List.of(new Cue(0, 1000, "用户对齐字幕")));
        var manual = persistence.save(task, "default", "manual", new Save(revision(task), brief.briefId(), null, supplied));
        assertThat(codec.read(manual.documentJson()).path("subtitleTimingSource").asText()).isEqualTo("USER_PROVIDED");
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("1")));
        var lease = claim(job, "storyboard-worker");
        conflict(() -> persistence.finish(job, lease, supplied, List.of()));
        assertThat(count("creator_creation_storyboard", task)).isEqualTo(1);
        assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNull();
        assertThat(runs.run(task, "default", job.runId()).orElseThrow().status()).isEqualTo("RUNNING");
    }

    @Test
    void preparationRequiresBudgetAndSameKeyNeverCreatesAdditionalApprovalOrRun() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        conflict(() -> persistence.generate(task, "default", "no-budget", new Generate(revision(task), brief.briefId(), cny("1"))));
        assertThat(count("creator_creation_approval", task)).isZero();
        initialize(task);
        var input = new Generate(revision(task), brief.briefId(), cny("2"));
        var first = persistence.generate(task, "default", "prepare", input);
        long after = revision(task);
        assertThat(persistence.generate(task, "default", "prepare", input).runId()).isEqualTo(first.runId());
        conflict(() -> persistence.generate(task, "default", "prepare", new Generate(input.expectedWorkspaceRevision(), brief.briefId(), cny("3"))));
        assertThat(revision(task)).isEqualTo(after);
        assertThat(count("creator_creation_approval", task)).isEqualTo(1);
        assertThat(count("creator_creation_run", task)).isEqualTo(1);
        assertThat(count("creator_creation_storyboard_job", task)).isEqualTo(1);
        var run = runs.run(task, "default", first.runId()).orElseThrow();
        assertThat(run.kind()).isEqualTo("PREPARE");
        assertThat(run.status()).isEqualTo("QUEUED");
        var frozen = codec.read(run.requestJson());
        assertThat(frozen.path("price").path("model").asText()).isEqualTo("configured-test-text-model");
        assertThat(frozen.path("price").path("basis").asText()).isEqualTo("CONFIGURED_ESTIMATE");
        assertThat(frozen.path("context").path("brief").path("topic").asText()).isEqualTo("真实素材与分镜测试");
        assertThat(runtime.snapshot(task, "default").executionAvailable()).isFalse();
        verify(generator, never()).generate(anyString(), anyString(), anyInt());
    }

    @Test
    void preparationEstimateIsReadOnlyUsesConfiguredUpperBoundAndHidesStorageIdentity() {
        String task = task("default");
        String asset = asset(task, "default", "same-name.png", "a");
        material(task, "只读估价必须包含本次实际输入的中文材料");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of(asset)));
        initialize(task);
        long expected = revision(task);
        var input = new EstimateInput(expected, brief.briefId());
        var estimate = persistence.estimate(task, "default", input);
        assertThat(persistence.estimate(task, "default", input)).isEqualTo(estimate);
        var value = codec.read(codec.write(estimate));
        assertThat(value.path("workspaceRevision").asLong()).isEqualTo(expected);
        assertThat(value.path("maxOutputTokens").asInt()).isEqualTo(4096);
        assertThat(value.path("inputTokenUpperBound").asLong()).isGreaterThan(4096);
        assertThat(value.path("model").asText()).isEqualTo("configured-test-text-model");
        assertThat(value.path("basis").asText()).isEqualTo("CONFIGURED_ESTIMATE");
        var expectedCost = prices.getTextInputUsdPerMillionTokens().multiply(BigDecimal.valueOf(value.path("inputTokenUpperBound").asLong()))
                .add(prices.getTextOutputUsdPerMillionTokens().multiply(new BigDecimal("4096")))
                .multiply(prices.getUsdToCny()).divide(new BigDecimal("1000000"), 6, RoundingMode.CEILING);
        assertThat(value.path("estimatedMaxAdditionalCostCny").decimalValue()).isEqualByComparingTo(expectedCost);
        assertThat(codec.write(estimate)).doesNotContain("bucketName", "objectKey", "isolated-storyboard", "users/default/", "signature");
        assertThat(revision(task)).isEqualTo(expected);
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        assertThat(count("creator_creation_approval", task)).isZero();
        assertThat(count("creator_creation_run", task)).isZero();
        assertThat(count("creator_creation_storyboard_job", task)).isZero();
        assertThat(count("creator_creation_cost_entry", task)).isZero();
        assertThat(count("creator_creation_brief", task)).isEqualTo(1);
        assertThat(count("creator_creation_storyboard", task)).isZero();
        verify(generator, never()).generate(anyString(), anyString(), anyInt());
    }

    @Test
    void authorizationBelowEstimatedCostRejectsWithoutApprovalRunOrJob() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        long expected = revision(task);
        var estimate = codec.read(codec.write(persistence.estimate(task, "default", new EstimateInput(expected, brief.briefId()))));
        var reservation = estimate.path("estimatedMaxAdditionalCostCny").decimalValue();
        assertThat(reservation).isGreaterThan(cny("0.000001"));
        conflict(() -> persistence.generate(task, "default", "insufficient-confirmation",
                new Generate(expected, brief.briefId(), cny("0.000001"))));
        assertThat(revision(task)).isEqualTo(expected);
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        assertThat(count("creator_creation_approval", task)).isZero();
        assertThat(count("creator_creation_run", task)).isZero();
        assertThat(count("creator_creation_storyboard_job", task)).isZero();
        assertThat(count("creator_creation_cost_entry", task)).isZero();
        verify(generator, never()).generate(anyString(), anyString(), anyInt());
        var accepted = persistence.generate(task, "default", "exact-confirmation", new Generate(expected, brief.briefId(), reservation));
        var queued = runs.run(task, "default", accepted.runId()).orElseThrow();
        assertThat(codec.read(queued.requestJson()).path("price").path("reservationCny").decimalValue()).isEqualByComparingTo(reservation);
        assertThat(codec.read(queued.requestJson()).path("price").path("inputTokenUpperBound").asLong())
                .isEqualTo(estimate.path("inputTokenUpperBound").asLong());
        assertThat(count("creator_creation_approval", task)).isEqualTo(1);
        assertThat(count("creator_creation_run", task)).isEqualTo(1);
        assertThat(count("creator_creation_storyboard_job", task)).isEqualTo(1);
    }

    @Test
    void jobAdmissionFailureRollsBackBudgetApprovalRunAndWorkspaceActivation() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        long expected = revision(task);
        var failingMapper = spy(mapper);
        doThrow(new IllegalStateException("受控任务写入失败")).when(failingMapper).insertJob(any(Job.class));
        var failing = service(failingMapper);
        assertThatThrownBy(() -> failing.generate(task, "default", "prepare", new Generate(expected, brief.briefId(), cny("2"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("受控任务写入失败");
        assertThat(count("creator_creation_approval", task)).isZero();
        assertThat(count("creator_creation_run", task)).isZero();
        assertThat(count("creator_creation_storyboard_job", task)).isZero();
        assertThat(revision(task)).isEqualTo(expected);
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        assertThat(runtime.snapshot(task, "default").budget().limitCny()).isEqualByComparingTo("20");
    }

    @Test
    void finishIsAtomicAndOnlyCompletesAfterFeeSettled() {
        String task = task("default");
        String asset = asset(task, "default", "same-name.png", "a");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of(asset)));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2")));
        var lease = claim(job, "storyboard-worker");
        String charge = "storyboard-" + job.runId();
        runtime.reserve(task, "default", job.runId(), lease.leaseOwner(), lease.leaseGeneration(), charge, "TEXT", cny("0.2"), "{\"model\":\"fixture\"}");
        assertThat(runtime.markSent(task, "default", charge, lease.leaseOwner(), lease.leaseGeneration())).isTrue();
        var candidate = candidate(asset, List.of("brief:user"), List.of());
        var frozen = persistence.snapshotAssets(task, "default", List.of(asset));
        conflict(() -> persistence.finish(job, lease, candidate, frozen));
        assertThat(count("creator_creation_storyboard", task)).isZero();
        assertThat(count("creator_creation_storyboard_asset", task)).isZero();
        assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNull();
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isEqualTo(job.runId());
        assertThat(runs.run(task, "default", job.runId()).orElseThrow().status()).isEqualTo("RUNNING");
        runtime.settle(task, "default", charge, cny("0.1"), "USAGE_ESTIMATE", "fixture:usage");
        var board = persistence.finish(job, lease, candidate, frozen);
        assertThat(board.runId()).isEqualTo(job.runId());
        assertThat(board.revision()).isEqualTo(1);
        assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isEqualTo(board.storyboardId());
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        var completed = runs.run(task, "default", job.runId()).orElseThrow();
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(codec.read(completed.resultJson()).path("storyboardId").asText()).isEqualTo(board.storyboardId());
        assertThat(codec.read(board.documentJson()).path("subtitleTimingSource").asText()).isEqualTo("NOT_PROVIDED");
        assertThat(runtime.findCharge(task, "default", job.runId(), charge).orElseThrow().status()).isEqualTo("SETTLED");
        assertThat(runtime.snapshot(task, "default").budget().knownCny()).isEqualByComparingTo("0.1");
        assertThat(runtime.snapshot(task, "default").budget().reservedCny()).isZero();
        assertThat(persistence.finish(job, lease, candidate, frozen).storyboardId()).isEqualTo(board.storyboardId());
        assertThat(count("creator_creation_storyboard", task)).isEqualTo(1);
        assertThat(mapper.references(task, "default", asset, 0, 20)).hasSize(1);
    }

    @Test
    void expiredOrReplacedLeaseCannotSaveCandidate() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("1")));
        var old = claim(job, "old-worker");
        assertThat(runtime.claim(task, "default", job.runId(), "new-worker", 30)).isEmpty();
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE run_id=?", job.runId());
        conflict(() -> persistence.finish(job, old, candidate(null, List.of("brief:user"), List.of()), List.of()));
        var replacement = claim(job, "new-worker");
        assertThat(replacement.leaseGeneration()).isEqualTo(old.leaseGeneration() + 1);
        conflict(() -> persistence.finish(job, old, candidate(null, List.of("brief:user"), List.of()), List.of()));
        assertThat(count("creator_creation_storyboard", task)).isZero();
        assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNull();
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isEqualTo(job.runId());
        var saved = persistence.finish(job, replacement, candidate(null, List.of("brief:user"), List.of()), List.of());
        assertThat(saved.runId()).isEqualTo(job.runId());
    }

    @Test
    void cancellationBeforeSavePreservesHistoryAndRejectsLateCandidate() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("1")));
        var lease = claim(job, "storyboard-worker");
        assertThat(runtime.cancel(task, "default", job.runId()).status()).isEqualTo("CANCEL_REQUESTED");
        conflict(() -> persistence.finish(job, lease, candidate(null, List.of("brief:user"), List.of()), List.of()));
        assertThat(count("creator_creation_storyboard", task)).isZero();
        assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNull();
        runtime.acknowledgeCancellation(task, "default", job.runId(), lease.leaseOwner(), lease.leaseGeneration());
        assertThat(runs.run(task, "default", job.runId()).orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
    }

    @Test
    void unknownFeeBlocksNewPhysicalCallEvenAfterNewPreparationWasConfirmed() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var first = persistence.generate(task, "default", "first", new Generate(revision(task), brief.briefId(), cny("2")));
        var lease = claim(first, "storyboard-worker");
        runtime.reserve(task, "default", first.runId(), lease.leaseOwner(), lease.leaseGeneration(), "unknown-first", "TEXT", cny("0.2"), "{\"basis\":\"fixture\"}");
        runtime.markSent(task, "default", "unknown-first", lease.leaseOwner(), lease.leaseGeneration());
        runtime.markUnknown(task, "default", "unknown-first");
        runtime.fail(task, "default", first.runId(), lease.leaseOwner(), lease.leaseGeneration(), "SUPPLIER_FAILED");
        var second = persistence.generate(task, "default", "second", new Generate(revision(task), brief.briefId(), cny("2")));
        var newLease = claim(second, "other-worker");
        conflict(() -> runtime.reserve(task, "default", second.runId(), newLease.leaseOwner(), newLease.leaseGeneration(), "must-not-send", "TEXT", cny("0.2"), "{\"basis\":\"fixture\"}"));
        assertThat(runtime.findCharge(task, "default", second.runId(), "must-not-send")).isEmpty();
        assertThat(runtime.snapshot(task, "default").budget().unknownCny()).isEqualByComparingTo("0.2");
        assertThat(runtime.snapshot(task, "default").budget().paidCallsBlocked()).isTrue();
        verify(generator, never()).generate(anyString(), anyString(), anyInt());
    }

    @Test
    void workerSuccessUsesQueuedPricesAndSendsOnlySafeAssetCatalogToTextModel() {
        String task = task("default");
        String asset = asset(task, "default", "same-name.png", "a");
        String materialId = material(task, "模型只能引用这份真实材料");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of(asset)));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2")));
        // 排队后变更部署价格，已确认运行仍按入队快照结算，不能重新定价旧调用。
        prices.setUsdToCny(cny("9"));
        prices.setTextInputUsdPerMillionTokens(cny("10"));
        prices.setTextOutputUsdPerMillionTokens(cny("30"));
        when(generator.generate(anyString(), anyString(), eq(4096))).thenReturn(
                new StoryboardTextGenerator.Raw(codec.write(candidate(asset, List.of(materialId), List.of())), 1000, 500, "configured-test-text-model"));
        try (var worker = worker()) {
            worker.process(job);
        }
        var completed = runs.run(task, "default", job.runId()).orElseThrow();
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        var charge = runtime.findCharge(task, "default", job.runId(), "storyboard:" + job.runId()).orElseThrow();
        assertThat(charge.status()).isEqualTo("SETTLED");
        assertThat(charge.knownCostCny()).isEqualByComparingTo("0.004320");
        var queuedPrice = codec.read(completed.requestJson()).path("price");
        assertThat(charge.reservedCny()).isEqualByComparingTo(queuedPrice.path("reservationCny").decimalValue());
        assertThat(charge.reservedCny()).isLessThan(completed.authorizationLimitCny());
        assertThat(codec.read(charge.priceSnapshotJson()).path("usdToCny").decimalValue()).isEqualByComparingTo("7.20");
        assertThat(codec.read(charge.priceSnapshotJson()).path("inputUsdPerMillion").decimalValue()).isEqualByComparingTo("0.20");
        assertThat(codec.read(charge.priceSnapshotJson()).path("outputUsdPerMillion").decimalValue()).isEqualByComparingTo("0.80");
        var savedJob = mapper.job(task, "default", job.runId()).orElseThrow();
        assertThat(savedJob.storyboardId()).isNotNull();
        var board = mapper.board(task, "default", savedJob.storyboardId()).orElseThrow();
        assertThat(persistence.frozen(board)).extracting(Asset::assetId).containsExactly(asset);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(generator, times(1)).generate(anyString(), prompt.capture(), eq(4096));
        assertThat(prompt.getValue()).doesNotContain("bucketName", "objectKey", "isolated-storyboard", "users/default/", "signature");
        assertThat(codec.read(prompt.getValue()).path("assets").get(0).path("assetId").asText()).isEqualTo(asset);
        assertThat(codec.read(prompt.getValue()).path("sources").path("materials").get(0).path("sourceId").asText()).isEqualTo(materialId);
        assertThat(count("creator_creation_storyboard", task)).isEqualTo(1);
    }

    @Test
    void malformedModelJsonStillSettlesReportedUsageButSavesNoCandidate() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2")));
        when(generator.generate(anyString(), anyString(), eq(4096))).thenReturn(
                new StoryboardTextGenerator.Raw("{坏 JSON", 1000, 500, "configured-test-text-model"));
        try (var worker = worker()) {
            worker.process(job);
        }
        var failed = runs.run(task, "default", job.runId()).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("STORYBOARD_PREPARATION_FAILED");
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        var charge = runtime.findCharge(task, "default", job.runId(), "storyboard:" + job.runId()).orElseThrow();
        assertThat(charge.status()).isEqualTo("SETTLED");
        assertThat(charge.knownCostCny()).isEqualByComparingTo("0.004320");
        assertThat(count("creator_creation_storyboard", task)).isZero();
        assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNull();
        verify(generator, times(1)).generate(anyString(), anyString(), eq(4096));
    }

    @Test
    void missingOneUsageSideKeepsValidCandidateAndUnknownFeeBlocksNextModelCall() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2")));
        when(generator.generate(anyString(), anyString(), eq(4096))).thenReturn(
                new StoryboardTextGenerator.Raw(codec.write(candidate(null, List.of("brief:user"), List.of())), null, 500, "configured-test-text-model"));
        try (var worker = worker()) {
            worker.process(job);
        }
        assertThat(runs.run(task, "default", job.runId()).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNotNull();
        var charge = runtime.findCharge(task, "default", job.runId(), "storyboard:" + job.runId()).orElseThrow();
        assertThat(charge.status()).isEqualTo("UNKNOWN");
        assertThat(charge.knownCostCny()).isNull();
        var reservation = codec.read(runs.run(task, "default", job.runId()).orElseThrow().requestJson()).path("price").path("reservationCny").decimalValue();
        assertThat(reservation).isPositive().isLessThan(cny("2"));
        assertThat(charge.reservedCny()).isEqualByComparingTo(reservation);
        assertThat(runtime.snapshot(task, "default").budget().unknownCny()).isEqualByComparingTo(reservation);
        assertThat(runtime.snapshot(task, "default").budget().paidCallsBlocked()).isTrue();
        var next = persistence.generate(task, "default", "second-prepare", new Generate(revision(task), brief.briefId(), cny("1")));
        try (var worker = worker()) {
            worker.process(next);
        }
        assertThat(runs.run(task, "default", next.runId()).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(runtime.findCharge(task, "default", next.runId(), "storyboard:" + next.runId())).isEmpty();
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        assertThat(count("creator_creation_storyboard", task)).isEqualTo(1);
        verify(generator, times(1)).generate(anyString(), anyString(), eq(4096));
    }

    @Test
    void workerTakeoverOfPreviouslySentCallNeverPhysicallyCallsModelAgain() {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2")));
        var previous = claim(job, "lost-worker");
        String key = "storyboard:" + job.runId();
        var queuedPrice = codec.read(previous.requestJson()).path("price");
        runtime.reserve(task, "default", job.runId(), previous.leaseOwner(), previous.leaseGeneration(), key,
                "STORYBOARD_TEXT", queuedPrice.path("reservationCny").decimalValue(), codec.write(queuedPrice));
        runtime.markSent(task, "default", key, previous.leaseOwner(), previous.leaseGeneration());
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE run_id=?", job.runId());
        assertThat(mapper.candidates()).extracting(Job::runId).contains(job.runId());
        try (var worker = worker()) {
            worker.process(job);
        }
        var recovered = runs.run(task, "default", job.runId()).orElseThrow();
        assertThat(recovered.leaseGeneration()).isEqualTo(previous.leaseGeneration() + 1);
        assertThat(recovered.status()).isEqualTo("FAILED");
        assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
        assertThat(runtime.findCharge(task, "default", job.runId(), key).orElseThrow().status()).isEqualTo("UNKNOWN");
        assertThat(count("creator_creation_storyboard", task)).isZero();
        verify(generator, never()).generate(anyString(), anyString(), anyInt());
    }

    @Test
    void inFlightCancellationDiscardsLateCandidateButStillSettlesLateUsage() throws Exception {
        String task = task("default");
        var brief = persistence.saveBrief(task, "default", "brief", brief(0, List.of()));
        initialize(task);
        var job = persistence.generate(task, "default", "prepare", new Generate(revision(task), brief.briefId(), cny("2")));
        String chargeKey = "storyboard:" + job.runId();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ignoredInterrupt = new CountDownLatch(1);
        var lateSettled = new CountDownLatch(1);
        when(generator.generate(anyString(), anyString(), eq(4096))).thenAnswer(invocation -> {
            entered.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (release.getCount() != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("受控供应商等待超时");
                try {
                    if (!release.await(remaining, TimeUnit.NANOSECONDS)) throw new IllegalStateException("受控供应商等待超时");
                } catch (InterruptedException ignored) {
                    // 模拟已发出的供应商请求忽略取消，保持等待直到测试明确释放其晚结果。
                    ignoredInterrupt.countDown();
                }
            }
            return new StoryboardTextGenerator.Raw(codec.write(candidate(null, List.of("brief:user"), List.of())),
                    1000, 500, "configured-test-text-model");
        });
        // 此观测层仅在真实事务结算提交后通知测试；其它方法原样委托真实运行服务及数据库。
        var observedRuntime = mock(CreationRuntimeService.class, delegatesTo(runtime));
        doAnswer(invocation -> {
            var settled = runtime.settle(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5));
            lateSettled.countDown();
            return settled;
        }).when(observedRuntime).settle(eq(task), eq("default"), eq(chargeKey), any(BigDecimal.class), anyString(), anyString());
        var worker = new StoryboardWorker(mapper, observedRuntime, persistence, generator, codec, JSON);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var processing = executor.submit(() -> worker.process(job));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.cancel(task, "default", job.runId()).status()).isEqualTo("CANCEL_REQUESTED");
            processing.get(8, TimeUnit.SECONDS);
            assertThat(ignoredInterrupt.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(runs.run(task, "default", job.runId()).orElseThrow().status()).isEqualTo("CANCELLED");
            assertThat(runs.workspace(task, "default").orElseThrow().activeRunId()).isNull();
            assertThat(runtime.findCharge(task, "default", job.runId(), chargeKey).orElseThrow().status()).isEqualTo("UNKNOWN");
            assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNull();
            assertThat(count("creator_creation_storyboard", task)).isZero();
            release.countDown();
            assertThat(lateSettled.await(5, TimeUnit.SECONDS)).isTrue();
            var settled = runtime.findCharge(task, "default", job.runId(), chargeKey).orElseThrow();
            assertThat(settled.status()).isEqualTo("SETTLED");
            assertThat(settled.knownCostCny()).isEqualByComparingTo("0.004320");
            assertThat(runtime.snapshot(task, "default").budget().knownCny()).isEqualByComparingTo("0.004320");
            assertThat(runtime.snapshot(task, "default").budget().unknownCny()).isZero();
            assertThat(runs.run(task, "default", job.runId()).orElseThrow().status()).isEqualTo("CANCELLED");
            assertThat(mapper.job(task, "default", job.runId()).orElseThrow().storyboardId()).isNull();
            assertThat(count("creator_creation_storyboard", task)).isZero();
            verify(generator, times(1)).generate(anyString(), anyString(), eq(4096));
        } finally {
            release.countDown();
            worker.close();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private StoryboardWorker worker() {
        return new StoryboardWorker(mapper, runtime, persistence, generator, codec, JSON);
    }

    private StoryboardPersistence service(StoryboardMapper selected) {
        return tx(new StoryboardPersistence(selected, media, tasks, runs, workspace, runtime, assets,
                codec, JSON, prices, generator));
    }

    @SuppressWarnings("unchecked")
    private static <T> T tx(T target) {
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private static String task(String owner) {
        String task = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO creator_task(task_id,user_id,task_name,video_type,status) VALUES (?,?,'隔离故事板测试','知识分享','DRAFT')", task, owner);
        media.adoptMediaWorkspace(task, owner);
        return task;
    }

    private static String material(String task, String content) {
        jdbc.update("INSERT INTO creator_material(task_id,material_type,content) VALUES (?,'MANUSCRIPT',?)", task, content);
        return "material:" + jdbc.queryForObject("SELECT id FROM creator_material WHERE task_id=? AND material_type='MANUSCRIPT'", Long.class, task);
    }

    private static String asset(String task, String owner, String fileName, String hashCharacter) {
        String id = UUID.randomUUID().toString();
        String key = "users/" + owner + "/tasks/" + task + "/assets/" + id;
        String objects = codec.write(List.of(new StoredFile("NORMALIZED", key + "/normalized.png", "image/png", 123, hashCharacter.repeat(64))));
        // 只插入数据库准备事实，不把固定字节和 metadata 当作本轮真实上传、哈希或解码的证据。
        jdbc.update("""
                INSERT INTO creator_creation_asset(asset_id,task_id,owner_id,idempotency_key,kind,file_name,
                  content_type,expected_bytes,expected_sha256,bucket_name,source_key,status,source_sha256,metadata_json,objects_json)
                VALUES (?,?,?,?,'IMAGE',?,'image/png',123,?,'isolated-storyboard',?,'READY',?,
                  '{"width":640,"height":360,"format":"png"}',?)
                """, id, task, owner, "asset-" + id, fileName, hashCharacter.repeat(64), key + "/original.png", hashCharacter.repeat(64), objects);
        return id;
    }

    private static BriefInput brief(long expected, List<String> ids) {
        return new BriefInput(expected, "真实素材与分镜测试", "讲清楚用户提供的事实", "第一次制作视频的 UP 主", 5000,
                "NO_DIALOGUE", "USER_PROVIDED", ids, "");
    }

    private static Candidate candidate(String asset, List<String> sourceRefs, List<Cue> cues) {
        return new Candidate(List.of(new Scene("scene-1", asset == null ? "TITLE_CARD" : "IMAGE_CARD", "开场", asset == null ? List.of("用户提供的内容") : List.of(),
                "", 5000, asset, null, null, "CONTAIN", "NO_DIALOGUE", null, cues, sourceRefs)));
    }

    private static void initialize(String task) {
        runtime.initialize(task, "default", "budget", revision(task), cny("20"));
    }

    private static Run claim(Job job, String worker) {
        return runtime.claim(job.taskId(), job.ownerId(), job.runId(), worker, 30).orElseThrow();
    }

    private static long revision(String task) {
        return runs.workspace(task, "default").orElseThrow().workspaceRevision();
    }

    private static BigDecimal cny(String value) { return new BigDecimal(value); }

    private static int count(String table, String task) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id=?", Integer.class, task);
    }

    private static void conflict(Runnable action) { rejected(action, 409); }

    private static void rejected(Runnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(status));
    }
}
