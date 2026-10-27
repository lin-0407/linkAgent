package com.link.linkagent.creator.media.upload.service;

import com.link.linkagent.creator.bilibili.mapper.CreatorBilibiliMapper;
import com.link.linkagent.creator.bilibili.model.BilibiliAccountRecord;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoSyncPayload;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoVerificationResult;
import com.link.linkagent.creator.bilibili.model.BindBvRequest;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingRecord;
import com.link.linkagent.creator.bilibili.service.CreatorBilibiliService;
import com.link.linkagent.creator.bilibili.service.BilibiliVideoSyncPersistenceService;
import com.link.linkagent.creator.bilibili.service.BilibiliVideoSyncProvider;
import com.link.linkagent.creator.bilibili.service.CreatorPublishedVideoContextService;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoRecord;
import com.link.linkagent.creator.creation.mapper.CreationWorkspaceMapper;
import com.link.linkagent.creator.creation.mapper.CreationRuntimeMapper;
import com.link.linkagent.creator.creation.mapper.GeneratedDraftMapper;
import com.link.linkagent.creator.creation.mapper.FixedProjectJobMapper;
import com.link.linkagent.creator.creation.mapper.AutomaticProductionMapper;
import com.link.linkagent.creator.creation.service.AutomaticProductionPlanService;
import com.link.linkagent.creator.creation.service.FixedProjectJobPersistenceService;
import com.link.linkagent.creator.creation.service.FixedProjectJobFiles;
import com.link.linkagent.creator.creation.service.FixedProjectWorker;
import com.link.linkagent.creator.creation.service.CreationWorkerProperties;
import com.link.linkagent.creator.creation.service.GeneratedDraftPersistenceService;
import com.link.linkagent.creator.creation.service.GeneratedDraftProbePersistenceService;
import com.link.linkagent.creator.creation.service.GeneratedDraftProbeService;
import com.link.linkagent.creator.media.probe.model.MediaProbeResult;
import com.link.linkagent.creator.media.probe.service.FfprobeMediaProbeService;
import com.link.linkagent.creator.creation.model.*;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import com.link.linkagent.creator.creation.execution.ExecutionMapper;
import com.link.linkagent.creator.creation.execution.ExecutionModels;
import com.link.linkagent.creator.creation.execution.ExecutionPlanner;
import com.link.linkagent.creator.creation.execution.ExecutionService;
import com.link.linkagent.creator.creation.execution.ExecutionSampleRenderer;
import com.link.linkagent.creator.creation.render.FixedProjectRenderService;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCompiler;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardPersistence;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardProjectFiles;
import com.link.linkagent.creator.creation.voice.service.CreationVoiceService;
import com.link.linkagent.creator.creation.aivideo.service.CreationVideoService;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Run;
import com.link.linkagent.creator.media.processing.service.MediaProcessingService;
import com.link.linkagent.creator.media.processing.service.MediaProcessingCostEstimator;
import com.link.linkagent.creator.media.processing.model.MediaProcessingOptionsRequest;
import com.link.linkagent.creator.media.preflight.service.PreflightReviewService;
import com.link.linkagent.creator.media.preflight.service.PreflightEventPublisher;
import com.link.linkagent.creator.media.preflight.model.CreatePreflightReviewRequest;
import java.math.BigDecimal;
import com.link.linkagent.creator.creation.service.CreationWorkspacePersistenceService;
import com.link.linkagent.creator.creation.service.CreationWorkspaceService;
import com.link.linkagent.creator.media.storage.StoredObjectMetadata;
import com.link.linkagent.creator.media.storage.MediaStorageException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;

import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.config.ObjectStorageProperties;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.probe.service.DraftVideoProbeRecoveryService;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.management.service.MediaDeletionService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.media.upload.model.MediaUploadRecord;
import com.link.linkagent.creator.media.workflow.CreatorMediaWorkflowGateService;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.workflow.mapper.CreatorWorkflowMapper;
import com.link.linkagent.creator.production.mapper.ProductionPlanMapper;
import com.link.linkagent.creator.production.service.ProductionPlanPersistenceService;
import com.link.linkagent.creator.production.model.ProductionPlanRecord;
import com.link.linkagent.creator.report.mapper.CreatorReviewInvalidationMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实隔离 MySQL 验证版本与事务；对象存储替身只用于断言不会误删原片。 */
@Testcontainers
class MediaVersionPersistenceIntegrationTest {
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "creation-isolated-test")
            .withEnv("MYSQL_ROOT_HOST", "%")
            .withEnv("MYSQL_DATABASE", "link_agent")
            .withLabels(Map.of("linkagent.scope", "creation-s1"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("sql/init.sql"),
                    "/docker-entrypoint-initdb.d/01-init.sql")
            .withExposedPorts(3306)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    static MediaUploadMapper mapper;
    static MediaUploadPersistenceService persistence;
    static JdbcTemplate jdbc;
    static TransactionTemplate transactions;
    static ProductionPlanPersistenceService production;
    static MediaProcessingMapper processing;
    static PreflightReviewMapper reviews;
    static CreatorBilibiliMapper bilibili;
    static CreatorBilibiliService bindingService;
    static BilibiliVideoSyncPersistenceService syncing;
    static CreatorMediaWorkflowGateService readyGate;
    static CreationWorkspaceMapper workspaceMapper;
    static CreationWorkspacePersistenceService selections;
    static CreationRuntimeMapper runtimeMapper;
    static ExecutionMapper executionMapper;
    static CreationRuntimeService runtime;
    static GeneratedDraftPersistenceService generated;
    static GeneratedDraftMapper generatedMapper;
    static FixedProjectJobMapper fixedJobMapper;
    static FixedProjectJobPersistenceService fixedJobs;
    static AutomaticProductionMapper automaticMapper;
    static AutomaticProductionPlanService automaticPlans;
    static com.link.linkagent.creator.task.service.CreatorTaskService taskService;
    static ProductionPlanMapper productionMapper;
    static GeneratedDraftProbePersistenceService generatedProbes;
    static com.link.linkagent.creator.creation.mapper.CreationMediaMapper mediaLinks;
    static com.link.linkagent.creator.creation.mapper.CreationPackageMapper creationPackages;
    static com.link.linkagent.creator.creation.service.CreationMediaService creationMedia;
    final List<FixedProjectJob> fixedJobsToClean = new ArrayList<>();

    @BeforeAll
    static void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:mysql://" + MYSQL.getHost() + ":"
                + MYSQL.getMappedPort(3306) + "/link_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "root", "creation-isolated-test");
        jdbc = new JdbcTemplate(source);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(MediaUploadMapper.class);
        configuration.addMapper(ProductionPlanMapper.class);
        configuration.addMapper(CreatorReviewInvalidationMapper.class);
        configuration.addMapper(MediaProcessingMapper.class);
        configuration.addMapper(PreflightReviewMapper.class);
        configuration.addMapper(CreatorBilibiliMapper.class);
        configuration.addMapper(CreatorTaskMapper.class);
        configuration.addMapper(CreationWorkspaceMapper.class);
        configuration.addMapper(CreationRuntimeMapper.class);
        configuration.addMapper(GeneratedDraftMapper.class);
        configuration.addMapper(FixedProjectJobMapper.class);
        configuration.addMapper(AutomaticProductionMapper.class);
        configuration.addMapper(ExecutionMapper.class);
        configuration.addMapper(com.link.linkagent.creator.creation.mapper.CreationMediaMapper.class);
        configuration.addMapper(com.link.linkagent.creator.creation.mapper.CreationPackageMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.afterPropertiesSet();
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(MediaUploadMapper.class);
        var manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        var proxy = new ProxyFactory(new MediaUploadPersistenceService(mapper));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        persistence = (MediaUploadPersistenceService) proxy.getProxy();
        var session = new SqlSessionTemplate(factory.getObject());
        executionMapper = session.getMapper(ExecutionMapper.class);
        productionMapper = session.getMapper(ProductionPlanMapper.class);
        var taskProxy = new ProxyFactory(new com.link.linkagent.creator.task.service.CreatorTaskService(session.getMapper(CreatorTaskMapper.class)));
        taskProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        taskService = (com.link.linkagent.creator.task.service.CreatorTaskService) taskProxy.getProxy();
        processing = session.getMapper(MediaProcessingMapper.class);
        reviews = session.getMapper(PreflightReviewMapper.class);
        var productionProxy = new ProxyFactory(new ProductionPlanPersistenceService(
                session.getMapper(ProductionPlanMapper.class), session.getMapper(CreatorReviewInvalidationMapper.class), mapper));
        productionProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        production = (ProductionPlanPersistenceService) productionProxy.getProxy();
        bilibili = session.getMapper(CreatorBilibiliMapper.class);
        var properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        readyGate = new CreatorMediaWorkflowGateService(properties, mapper, processing, reviews,
                mock(CreatorWorkflowMapper.class), mock(CreatorSuggestionMapper.class),
                mock(DraftVideoProbeRecoveryService.class));
        workspaceMapper = session.getMapper(CreationWorkspaceMapper.class);
        runtimeMapper = session.getMapper(CreationRuntimeMapper.class);
        mediaLinks = session.getMapper(com.link.linkagent.creator.creation.mapper.CreationMediaMapper.class);
        creationPackages = session.getMapper(com.link.linkagent.creator.creation.mapper.CreationPackageMapper.class);
        var runtimeProxy = new ProxyFactory(new CreationRuntimeService(mapper, runtimeMapper, workspaceMapper,
                mediaLinks, creationPackages, new ObjectMapper()));
        runtimeProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        runtime = (CreationRuntimeService) runtimeProxy.getProxy();
        generatedMapper = session.getMapper(GeneratedDraftMapper.class);
        var mediaProxy = new ProxyFactory(new com.link.linkagent.creator.creation.service.CreationMediaService(
                mediaLinks, generatedMapper, runtime));
        mediaProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        creationMedia = (com.link.linkagent.creator.creation.service.CreationMediaService) mediaProxy.getProxy();
        fixedJobMapper = session.getMapper(FixedProjectJobMapper.class);
        automaticMapper = session.getMapper(AutomaticProductionMapper.class);
        var automaticProxy = new ProxyFactory(new AutomaticProductionPlanService(mapper, session.getMapper(ProductionPlanMapper.class),
                runtimeMapper, runtime, automaticMapper, generatedMapper, fixedJobMapper, new ObjectMapper()));
        automaticProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        automaticPlans = (AutomaticProductionPlanService) automaticProxy.getProxy();
        var generatedProxy = new ProxyFactory(new GeneratedDraftPersistenceService(mapper, runtimeMapper, generatedMapper, new ObjectMapper(), runtime, automaticPlans));
        generatedProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        generated = (GeneratedDraftPersistenceService) generatedProxy.getProxy();
        var probeProxy = new ProxyFactory(new GeneratedDraftProbePersistenceService(runtime,runtimeMapper,generatedMapper,fixedJobMapper,mapper,new ObjectMapper()));
        probeProxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        generatedProbes = (GeneratedDraftProbePersistenceService) probeProxy.getProxy();
        var fixedProxy = new ProxyFactory(new FixedProjectJobPersistenceService(mapper, runtimeMapper, fixedJobMapper, new ObjectMapper()));
        fixedProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        fixedJobs = (FixedProjectJobPersistenceService) fixedProxy.getProxy();
        var selectionProxy = new ProxyFactory(new CreationWorkspacePersistenceService(mapper, workspaceMapper, readyGate));
        selectionProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        selections = (CreationWorkspacePersistenceService) selectionProxy.getProxy();
        var syncProxy = new ProxyFactory(new BilibiliVideoSyncPersistenceService(bilibili, readyGate));
        syncProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        syncing = (BilibiliVideoSyncPersistenceService) syncProxy.getProxy();
        var bindingProxy = new ProxyFactory(new CreatorBilibiliService(bilibili, session.getMapper(CreatorTaskMapper.class),
                mock(BilibiliVideoSyncProvider.class), syncing, readyGate));
        bindingProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        bindingService = (CreatorBilibiliService) bindingProxy.getProxy();
        bilibili.insertAccount(new BilibiliAccountRecord(null, "isolated-account", "default", "27058248",
                "隔离测试账号", null, "ACTIVE", null, null, null, null));
    }

    @Test
    void budgetInitializationMustBeExplicitIdempotentAndPreserveLegacyVideo() {
        String task = task(); var old = draft(upload(task, "legacy-budget", "legacy-budget"), 1);
        mapper.insertDraftVideo(old);
        assertThat(runtime.snapshot(task, "default").initialized()).isFalse();
        assertThat(revision(task)).isEqualTo(-1);
        var initialized = runtime.initialize(task, "default", "budget", -1, cny("20"));
        assertThat(initialized.initialized()).isTrue();
        assertThat(initialized.executionAvailable()).isFalse();
        assertThat(initialized.budgetScope()).isEqualTo("CREATION_RUNTIME_ONLY");
        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionId()).isEqualTo(old.versionId());
        long after = revision(task);
        runtime.initialize(task, "default", "budget", -1, cny("20"));
        assertThat(revision(task)).isEqualTo(after);
        assertThatThrownBy(() -> runtime.initialize(task, "default", "budget", -1, cny("21"))).hasMessageContaining("已初始化");
        assertThatThrownBy(() -> runtime.initialize(task, "other", "budget", -1, cny("20"))).hasMessageContaining("不存在");
    }

    @Test
    void preparationApprovalMustBindCanonicalInputAndRejectUnconfirmedFullRuns() {
        String task = task(); runtime.initialize(task, "default", "budget", -1, cny("20"));
        String input = "{\"script\":\"稿件\",\"durationMs\":90000}";
        long expected = revision(task);
        var approval = runtime.approvePreparation(task, "default", "approve", expected, input, cny("3"));
        assertThat(runtime.approvePreparation(task, "default", "approve", expected,
                "{\"durationMs\":90000,\"script\":\"稿件\"}", cny("3")).approvalId()).isEqualTo(approval.approvalId());
        long startRevision = revision(task);
        assertThatThrownBy(() -> runtime.start(task, "default", "FULL", "start", startRevision, approval.approvalId(), input))
                .hasMessageContaining("仅接受");
        assertThatThrownBy(() -> runtime.start(task, "default", "TEXT", "start", startRevision, approval.approvalId(), "{\"script\":\"变化\"}"))
                .hasMessageContaining("不一致");
        var run = runtime.start(task, "default", "TEXT", "start", startRevision, approval.approvalId(), input);
        assertThat(runtime.start(task, "default", "TEXT", "start", startRevision, approval.approvalId(),
                "{\"durationMs\":90000,\"script\":\"稿件\"}").runId()).isEqualTo(run.runId());
        assertThatThrownBy(() -> runtime.start(task, "default", "PREPARE", "start", startRevision, approval.approvalId(), input))
                .hasMessageContaining("内容不同");
        assertThat(count("creator_creation_run", task)).isEqualTo(1);
    }

    @Test
    void simultaneousStartsMustCreateOnlyOneActiveRun() throws Exception {
        String task = task(); runtime.initialize(task, "default", "budget", -1, cny("20"));
        var approval = runtime.approvePreparation(task, "default", "approve", revision(task), "{\"script\":1}", cny("5"));
        long expected = revision(task); var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) {
                String key = "start-" + i;
                results.add(pool.submit(() -> { go.await(); try {
                    runtime.start(task, "default", "TEXT", key, expected, approval.approvalId(), "{\"script\":1}"); return true;
                } catch (ResponseStatusException exception) { return false; } }));
            }
            go.countDown();
            assertThat(List.of(results.get(0).get(15, TimeUnit.SECONDS), results.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(count("creator_creation_run", task)).isEqualTo(1);
        assertThat(mapper.countActiveCreationRuns(task, "default")).isEqualTo(1);
    }

    @Test
    void queuedCancellationMustReleaseSlotAndRetryMustKeepHistory() {
        String task = task(); var first = preparedRun(task, "5");
        long before = revision(task);
        assertThat(runtime.cancel(task, "default", first.runId()).status()).isEqualTo("CANCELLED");
        assertThat(revision(task)).isEqualTo(before + 1);
        assertThat(runtime.snapshot(task, "default").activeRun()).isNull();
        runtime.cancel(task, "default", first.runId());
        assertThat(revision(task)).isEqualTo(before + 1);
        var retry = runtime.retry(task, "default", first.runId(), "retry", revision(task));
        assertThat(retry.runId()).isNotEqualTo(first.runId());
        assertThat(retry.parentRunId()).isEqualTo(first.runId());
        assertThat(runtime.getRun(task, "default", first.runId()).status()).isEqualTo("CANCELLED");
    }

    @Test
    void runningCancellationMustHoldSlotAndSentBudgetUntilAcknowledged() {
        String task = task(); var run = claimed(preparedRun(task, "5"), "worker");
        reserve(run, "sent", "2"); reserve(run, "unsent", "1");
        assertThat(runtime.markSent(task, "default", run.runId() + "sent", "worker", run.leaseGeneration())).isTrue();
        assertThat(runtime.markSent(task, "default", run.runId() + "sent", "worker", run.leaseGeneration())).isFalse();
        assertThat(runtime.cancel(task, "default", run.runId()).status()).isEqualTo("CANCEL_REQUESTED");
        assertThat(mapper.countActiveCreationRuns(task, "default")).isEqualTo(1);
        assertThat(runtime.snapshot(task, "default").budget().reservedCny()).isEqualByComparingTo("3");
        assertThatThrownBy(() -> runtime.complete(task, "default", run.runId(), "worker", run.leaseGeneration(), "{\"done\":true}"))
                .isInstanceOf(ResponseStatusException.class);
        runtime.acknowledgeCancellation(task, "default", run.runId(), "worker", run.leaseGeneration());
        var budget = runtime.snapshot(task, "default").budget();
        assertThat(budget.reservedCny()).isZero(); assertThat(budget.unknownCny()).isEqualByComparingTo("2");
        assertThat(budget.paidCallsBlocked()).isTrue();
        assertThat(runtimeMapper.charge(task, "default", run.runId() + "unsent").orElseThrow().status()).isEqualTo("RELEASED");
        assertThat(runtime.snapshot(task, "default").activeRun()).isNull();
    }

    @Test
    void lateSettlementAfterCancelMustBeRecordedOnceAndReleaseUnknownHold() {
        String task = task(); var run = claimed(preparedRun(task, "5"), "worker"); reserve(run, "late", "2");
        String key = run.runId() + "late";
        runtime.markSent(task, "default", key, "worker", run.leaseGeneration());
        runtime.cancel(task, "default", run.runId());
        runtime.acknowledgeCancellation(task, "default", run.runId(), "worker", run.leaseGeneration());
        runtime.settle(task, "default", key, cny("1.5"), "PROVIDER_REPORTED", "receipt-1");
        runtime.settle(task, "default", key, cny("1.5"), "PROVIDER_REPORTED", "receipt-1");
        var budget = runtime.snapshot(task, "default").budget();
        assertThat(budget.knownCny()).isEqualByComparingTo("1.5"); assertThat(budget.unknownCny()).isZero();
        assertThat(budget.paidCallsBlocked()).isFalse();
        assertThatThrownBy(() -> runtime.settle(task, "default", key, cny("1.6"), "PROVIDER_REPORTED", "receipt-1"))
                .hasMessageContaining("不一致");
        assertThat(runtime.getRun(task, "default", run.runId()).status()).isEqualTo("CANCELLED");
    }

    @Test
    void expiredLeaseMustFenceOldResultsAndMoveSentCallsToUnknown() {
        String task = task(); var old = claimed(preparedRun(task, "5"), "worker");
        reserve(old, "sent", "2"); reserve(old, "unsent", "1");
        runtime.markSent(task, "default", old.runId() + "sent", "worker", old.leaseGeneration());
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE run_id=?", old.runId());
        var current = claimed(old, "replacement");
        assertThat(current.leaseGeneration()).isEqualTo(old.leaseGeneration() + 1);
        assertThat(runtime.heartbeat(task, "default", old.runId(), "worker", old.leaseGeneration(), 30)).isFalse();
        assertThatThrownBy(() -> runtime.markSent(task, "default", old.runId() + "unsent", "worker", old.leaseGeneration()))
                .hasMessageContaining("失效");
        assertThatThrownBy(() -> runtime.complete(task, "default", old.runId(), "worker", old.leaseGeneration(), "{\"done\":true}"))
                .hasMessageContaining("租约");
        assertThat(runtime.snapshot(task, "default").budget().unknownCny()).isEqualByComparingTo("2");
        assertThatThrownBy(() -> reserve(current, "new", "1")).hasMessageContaining("暂停");
        runtime.settle(task, "default", old.runId() + "sent", cny("1"), "USAGE_ESTIMATE", "usage-1");
        assertThat(runtime.complete(task, "default", old.runId(), "replacement", current.leaseGeneration(), "{\"done\":true}").status()).isEqualTo("COMPLETED");
    }

    @Test
    void waitingDependencyMustRetainSlotAndAllowCancellationRecovery() {
        String task = task(); var run = claimed(preparedRun(task, "5"), "worker");
        var waiting = runtime.waitForDependency(task, "default", run.runId(), "worker", run.leaseGeneration(), "asset-job");
        assertThat(waiting.status()).isEqualTo("WAITING_DEPENDENCY"); assertThat(waiting.leaseOwner()).isNull();
        assertThat(runtime.claim(task, "default", run.runId(), "other-worker", 30)).isEmpty();
        assertThatThrownBy(() -> runtime.wake(task, "default", run.runId(), waiting.runRevision() - 1)).hasMessageContaining("已变化");
        var resumed = runtime.wake(task, "default", run.runId(), waiting.runRevision());
        var resumedRun = claimed(resumed, "worker");
        runtime.waitForDependency(task, "default", resumedRun.runId(), "worker", resumedRun.leaseGeneration(), "asset-job-2");
        runtime.cancel(task, "default", resumedRun.runId());
        var recovery = claimed(resumedRun, "cancel-worker");
        assertThat(recovery.status()).isEqualTo("CANCEL_REQUESTED");
        runtime.acknowledgeCancellation(task, "default", run.runId(), "cancel-worker", recovery.leaseGeneration());
        assertThat(runtime.snapshot(task, "default").activeRun()).isNull();
    }

    @Test
    void concurrentReservationsMustNotExceedApproval() throws Exception {
        String task = task(); var run = claimed(preparedRun(task, "5"), "worker");
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) { String key = "reserve-" + i;
                results.add(pool.submit(() -> { go.await(); try { reserve(run, key, "3"); return true;
                } catch (ResponseStatusException exception) { return false; } }));
            }
            go.countDown();
            assertThat(List.of(results.get(0).get(15, TimeUnit.SECONDS), results.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(runtime.snapshot(task, "default").budget().reservedCny()).isEqualByComparingTo("3");
    }

    @Test
    void unknownUsageMustKeepReservationAndBlockFurtherPaidCalls() {
        String task = task(); var run = claimed(preparedRun(task, "5"), "worker"); reserve(run, "unknown", "2");
        String key = run.runId() + "unknown";
        assertThatThrownBy(() -> runtime.markUnknown(task, "default", key)).hasMessageContaining("已发送");
        runtime.markSent(task, "default", key, "worker", run.leaseGeneration());
        runtime.markUnknown(task, "default", key); runtime.markUnknown(task, "default", key);
        assertThatThrownBy(() -> reserve(run, "another", "1")).hasMessageContaining("暂停");
        assertThat(runtime.snapshot(task, "default").budget().remainingCny()).isEqualByComparingTo("18");
        runtime.settle(task, "default", key, cny("0"), "PROVIDER_REPORTED", "zero-charge-receipt");
        reserve(run, "another", "1");
    }

    @Test
    void actualOverrunMustBeRecordedAndBlockEvenWhenTaskHasRemainingBudget() {
        String task = task(); var run = claimed(preparedRun(task, "5"), "worker"); reserve(run, "over", "1");
        String key = run.runId() + "over"; runtime.markSent(task, "default", key, "worker", run.leaseGeneration());
        runtime.settle(task, "default", key, cny("2"), "PROVIDER_REPORTED", "bill-1");
        assertThat(runtime.snapshot(task, "default").budget().knownCny()).isEqualByComparingTo("2");
        assertThatThrownBy(() -> reserve(run, "next", "1")).hasMessageContaining("暂停");
    }

    @Test
    void retryMustShareAuthorizationAndBudgetIncreaseMustNotEnlargeIt() {
        String task = task(); var first = claimed(preparedRun(task, "3"), "worker"); reserve(first, "first", "2");
        runtime.markSent(task, "default", first.runId() + "first", "worker", first.leaseGeneration());
        runtime.settle(task, "default", first.runId() + "first", cny("2"), "USAGE_ESTIMATE", "usage-2");
        runtime.fail(task, "default", first.runId(), "worker", first.leaseGeneration(), "PROVIDER_ERROR");
        runtime.updateBudget(task, "default", revision(task), cny("40"));
        var retry = claimed(runtime.retry(task, "default", first.runId(), "retry", revision(task)), "worker");
        assertThatThrownBy(() -> reserve(retry, "too-much", "2")).hasMessageContaining("授权剩余");
        reserve(retry, "remaining", "1");
        assertThat(runtime.snapshot(task, "default").budget().knownCny()).isEqualByComparingTo("2");
    }

    @Test
    void budgetCannotBeLoweredBelowOccupiedAndEditingBudgetMustNotInvalidateLease() {
        String task = task(); var run = claimed(preparedRun(task, "5"), "worker"); reserve(run, "hold", "3");
        assertThatThrownBy(() -> runtime.updateBudget(task, "default", revision(task), cny("2"))).hasMessageContaining("不能低于");
        long expected = revision(task); runtime.updateBudget(task, "default", expected, cny("3"));
        assertThat(runtime.heartbeat(task, "default", run.runId(), "worker", run.leaseGeneration(), 30)).isTrue();
        assertThatThrownBy(() -> runtime.updateBudget(task, "default", expected, cny("4"))).hasMessageContaining("已变化");
        assertThatThrownBy(() -> runtime.complete(task, "default", run.runId(), "worker", run.leaseGeneration(), "{\"done\":true}"))
                .hasMessageContaining("结算");
    }

    @Test
    void zeroCostApprovalMustForbidPaidReservationsAndRevocationMustCancelRun() {
        String task = task(); var run = claimed(preparedRun(task, "0"), "worker");
        assertThatThrownBy(() -> reserve(run, "paid", "0.000001")).hasMessageContaining("授权剩余");
        runtime.revokeApproval(task, "default", run.approvalId());
        assertThat(runtime.getRun(task, "default", run.runId()).status()).isEqualTo("CANCEL_REQUESTED");
        runtime.acknowledgeCancellation(task, "default", run.runId(), "worker", run.leaseGeneration());
        assertThatThrownBy(() -> runtime.retry(task, "default", run.runId(), "retry", revision(task))).hasMessageContaining("授权已失效");
    }

    @Test
    void physicalChargeKeyMustBeIdempotentAndCannotBeReusedAcrossTasks() {
        String task = task(); var run = claimed(preparedRun(task, "5"), "worker"); reserve(run, "same", "1"); reserve(run, "same", "1");
        assertThat(count("creator_creation_cost_entry", task)).isEqualTo(1);
        assertThatThrownBy(() -> reserve(run, "same", "2")).hasMessageContaining("其他费用");
        var other = claimed(preparedRun(task(), "5"), "worker");
        assertThatThrownBy(() -> runtime.reserve(other.taskId(), "default", other.runId(), "worker", other.leaseGeneration(),
                run.runId() + "same", "LLM_TEXT", cny("1"), "{\"pricing\":\"test\"}")).hasMessageContaining("占用");
        assertThat(runtime.snapshot(other.taskId(), "default").budget().reservedCny()).isZero();
    }

    @Test
    void activeRunMustBlockUploadRepositionBindingSelectionAndDeletion() {
        String task = task(); var video = readyVideo(task, "ready"); var run = preparedRun(task, "5");
        assertThatThrownBy(() -> save(task, "blocked")).hasMessageContaining("制作运行");
        assertThatThrownBy(() -> production.startGenerating(plan(task, UUID.randomUUID().toString()))).hasMessageContaining("制作运行");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> readyGate.lockForVideoBinding(task, "default"))).hasMessageContaining("制作运行");
        assertThatThrownBy(() -> workspace(mockStorage()).select(task, "default", video.versionId(), revision(task)))
                .hasMessageContaining("运行");
        var storage = mockStorage(); var deletion = new MediaDeletionService(mapper, processing, reviews, storage);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> deletion.deleteMedia("default", task, video.versionId())))
                .hasMessageContaining("制作运行");
        verify(storage, never()).deleteObject(anyString(), anyString());
        var properties = new CreatorMediaProperties(); properties.setEnabled(true);
        var mediaJobs = new MediaProcessingService(properties, new MediaProcessingCostEstimator(properties),
                processing, mapper, storage, new ObjectMapper());
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> mediaJobs.createJob("default", task, video.versionId(),
                new MediaProcessingOptionsRequest(10, MediaProcessingOptionsRequest.Resolution.P720,
                        MediaProcessingOptionsRequest.ModelPlan.FLASH, false)))).hasMessageContaining("制作运行");
        var preflight = new PreflightReviewService(properties, reviews, mapper, processing,
                mock(PreflightEventPublisher.class), new ObjectMapper());
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> preflight.create("default", task, "blocked-review",
                new CreatePreflightReviewRequest(video.versionId(), true, null)))).hasMessageContaining("制作运行");
        runtime.cancel(task, "default", run.runId());
        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionId()).isEqualTo(video.versionId());
    }

    @Test
    void badJsonPrecisionAndForeignOwnershipMustNotCreateArtifacts() {
        String task = task(); runtime.initialize(task, "default", "budget", -1, cny("20"));
        for (String input : new String[]{"{}", "[]", "{\"a\":1,\"a\":2}", "{\"a\":1} {\"b\":2}", "invalid"}) {
            assertThatThrownBy(() -> runtime.approvePreparation(task, "default", "approve", revision(task), input, cny("1")))
                    .isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> runtime.updateBudget(task, "default", revision(task), cny("0.0000001"))).hasMessageContaining("金额格式");
        assertThat(count("creator_creation_approval", task)).isZero();
        var run = preparedRun(task, "5");
        assertThatThrownBy(() -> runtime.getRun(task, "other", run.runId())).hasMessageContaining("不存在");
        assertThatThrownBy(() -> runtime.cancel(task, "other", run.runId())).hasMessageContaining("不存在");
        assertThat(runtime.getRun(task, "default", run.runId()).status()).isEqualTo("QUEUED");
    }

    private static BigDecimal cny(String value) { return new BigDecimal(value); }

    @Test
    void preparationRequestMustSaveApprovalAndRunAtomically() {
        String task = task(); runtime.initialize(task, "default", "budget", -1, cny("20"));
        long expected = revision(task);
        assertThatThrownBy(() -> runtime.startPreparation(task, "default", "FULL", "invalid", expected,
                "{\"script\":1}", cny("1"))).hasMessageContaining("仅接受");
        assertThat(revision(task)).isEqualTo(expected);
        assertThat(count("creator_creation_approval", task)).isZero();
        var first = runtime.startPreparation(task, "default", "PREPARE", "prepare", expected, "{\"script\":1}", cny("1"));
        assertThat(runtime.startPreparation(task, "default", "PREPARE", "prepare", expected, "{\"script\":1}", cny("1")).runId())
                .isEqualTo(first.runId());
        assertThat(count("creator_creation_approval", task)).isEqualTo(1);
        assertThat(count("creator_creation_run", task)).isEqualTo(1);
    }

    @Test
    void taskBudgetMustConstrainReservationEvenWithLargerApproval() {
        String task = task(); var run = claimed(preparedRun(task, "5"), "worker");
        runtime.updateBudget(task, "default", revision(task), cny("2"));
        assertThatThrownBy(() -> reserve(run, "too-much", "3")).hasMessageContaining("任务剩余预算");
        runtime.updateBudget(task, "default", revision(task), cny("4"));
        reserve(run, "valid", "3");
        assertThat(runtime.snapshot(task, "default").budget().remainingCny()).isEqualByComparingTo("1");
    }

    @Test
    void rearmingMustRespectReducedBudgetAndRecoverAfterBudgetIsRaised() {
        var old = claimed(preparedRun(task(), "5"), "worker");
        reserve(old, "released", "3");
        var current = takeOver(old);
        String task = current.taskId(), key = current.runId() + "released";
        runtime.updateBudget(task, "default", revision(task), cny("1"));

        assertThatThrownBy(() -> runtime.rearm(task, "default", current.runId(), current.leaseOwner(),
                current.leaseGeneration(), key)).hasMessageContaining("任务剩余预算");
        assertThat(runtimeMapper.charge(task, "default", key).orElseThrow().status()).isEqualTo("RELEASED");
        assertThat(runtime.snapshot(task, "default").budget().reservedCny()).isZero();

        runtime.updateBudget(task, "default", revision(task), cny("3"));
        runtime.rearm(task, "default", current.runId(), current.leaseOwner(), current.leaseGeneration(), key);
        reserve(current, "released", "3");
        assertThat(runtime.markSent(task, "default", key, current.leaseOwner(), current.leaseGeneration())).isTrue();
        assertThat(runtime.markSent(task, "default", key, current.leaseOwner(), current.leaseGeneration())).isFalse();
        assertThat(runtime.snapshot(task, "default").budget().reservedCny()).isEqualByComparingTo("3");
    }

    @Test
    void rearmingMustRespectTheRemainingApprovalAfterOtherCallsSettled() {
        var old = claimed(preparedRun(task(), "5"), "worker");
        reserve(old, "released", "3");
        var current = takeOver(old);
        reserve(current, "later", "3");
        String task = current.taskId();
        runtime.markSent(task, "default", current.runId() + "later", current.leaseOwner(), current.leaseGeneration());
        runtime.settle(task, "default", current.runId() + "later", cny("3"), "PROVIDER_REPORTED", "later-bill");

        assertThatThrownBy(() -> runtime.rearm(task, "default", current.runId(), current.leaseOwner(),
                current.leaseGeneration(), current.runId() + "released")).hasMessageContaining("授权剩余金额");
        assertThat(runtime.snapshot(task, "default").budget().reservedCny()).isZero();
    }

    @Test
    void rearmingMustWaitForUnknownUsageToBeSettled() {
        var old = claimed(preparedRun(task(), "5"), "worker");
        reserve(old, "released", "2");
        reserve(old, "unknown", "1");
        String task = old.taskId(), key = old.runId() + "unknown";
        runtime.markSent(task, "default", key, old.leaseOwner(), old.leaseGeneration());
        var current = takeOver(old);

        assertThatThrownBy(() -> runtime.rearm(task, "default", current.runId(), current.leaseOwner(),
                current.leaseGeneration(), current.runId() + "released")).hasMessageContaining("暂停新增付费");
        assertThat(runtime.snapshot(task, "default").budget().unknownCny()).isEqualByComparingTo("1");
        assertThat(runtime.snapshot(task, "default").budget().reservedCny()).isZero();

        runtime.settle(task, "default", key, cny("0.5"), "PROVIDER_REPORTED", "confirmed-bill");
        runtime.rearm(task, "default", current.runId(), current.leaseOwner(), current.leaseGeneration(), current.runId() + "released");
        assertThat(runtime.snapshot(task, "default").budget().reservedCny()).isEqualByComparingTo("2");
    }

    @Test
    void rearmingMustNotBypassAnActualCostOverrun() {
        var old = claimed(preparedRun(task(), "5"), "worker");
        reserve(old, "released", "2");
        var current = takeOver(old);
        reserve(current, "overrun", "1");
        String task = current.taskId(), key = current.runId() + "overrun";
        runtime.markSent(task, "default", key, current.leaseOwner(), current.leaseGeneration());
        runtime.settle(task, "default", key, cny("2"), "PROVIDER_REPORTED", "overrun-bill");

        assertThatThrownBy(() -> runtime.rearm(task, "default", current.runId(), current.leaseOwner(),
                current.leaseGeneration(), current.runId() + "released")).hasMessageContaining("暂停新增付费");
        assertThat(runtime.snapshot(task, "default").budget().knownCny()).isEqualByComparingTo("2");
        assertThat(runtime.snapshot(task, "default").budget().reservedCny()).isZero();
    }

    private static Run takeOver(Run run) {
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE run_id=?", run.runId());
        return runtime.claim(run.taskId(), "default", run.runId(), "replacement", 60).orElseThrow();
    }

    @Test
    void unifiedSchemaMustBeIdempotentOnExistingTables() throws Exception {
        var result = MYSQL.execInContainer("sh", "-c", "mysql -uroot -pcreation-isolated-test < /docker-entrypoint-initdb.d/01-init.sql");
        assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
        String task = task(); var run = preparedRun(task, "1");
        assertThat(runtime.getRun(task, "default", run.runId()).status()).isEqualTo("QUEUED");
    }

    @Test
    void manualMediaJobAndCreationStartMustSerializeOnTask() throws Exception {
        String task = task(); var video = readyVideo(task, "ready");
        jdbc.update("UPDATE creator_draft_video SET duration_ms=30000,width=1280,height=720,has_audio=0 WHERE version_id=?", video.versionId());
        runtime.initialize(task, "default", "budget", revision(task), cny("20"));
        var approval = runtime.approvePreparation(task, "default", "approve", revision(task), "{\"script\":1}", cny("2"));
        long expected = revision(task);
        var properties = new CreatorMediaProperties(); properties.setEnabled(true);
        var jobs = new MediaProcessingService(properties, new MediaProcessingCostEstimator(properties), processing, mapper,
                mockStorage(), new ObjectMapper());
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var creation = pool.submit(() -> { go.await(); try {
                runtime.start(task, "default", "TEXT", "start", expected, approval.approvalId(), "{\"script\":1}"); return true;
            } catch (ResponseStatusException exception) { return false; } });
            var manual = pool.submit(() -> { go.await(); try {
                transactions.executeWithoutResult(status -> jobs.createJob("default", task, video.versionId(),
                        new MediaProcessingOptionsRequest(5, MediaProcessingOptionsRequest.Resolution.P720,
                                MediaProcessingOptionsRequest.ModelPlan.FLASH, false))); return true;
            } catch (ResponseStatusException exception) { return false; } });
            go.countDown();
            assertThat(List.of(creation.get(15, TimeUnit.SECONDS), manual.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(workspaceMapper.countActiveWork(task, "default")).isEqualTo(1);
    }

    private static Run preparedRun(String task, String cap) {
        if (!runtime.snapshot(task, "default").initialized()) runtime.initialize(task, "default", "budget", revision(task), cny("20"));
        var approval = runtime.approvePreparation(task, "default", "approve", revision(task), "{\"script\":\"测试\"}", cny(cap));
        return runtime.start(task, "default", "TEXT", "start", revision(task), approval.approvalId(), "{\"script\":\"测试\"}");
    }
    private static Run claimed(Run run, String worker) { return runtime.claim(run.taskId(), "default", run.runId(), worker, 60).orElseThrow(); }
    private static void reserve(Run run, String suffix, String amount) {
        runtime.reserve(run.taskId(), "default", run.runId(), run.leaseOwner(), run.leaseGeneration(), run.runId() + suffix,
                "LLM_TEXT", cny(amount), "{\"pricing\":\"test\"}");
    }

    @Test
    void legacyReadMustNotCreateWorkspaceAndExplicitEmptyPointerMustNotFallBack() {
        String task = task();
        var old = draft(upload(task, "legacy", "legacy"), 1);
        mapper.insertDraftVideo(old);
        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionId()).isEqualTo(old.versionId());
        assertThat(mapper.findMediaWorkspace(task, "default")).isEmpty();
        mapper.adoptMediaWorkspace(task, "default");
        jdbc.update("UPDATE creator_creation_workspace SET current_video_version_id = NULL WHERE task_id = ?", task);
        assertThat(mapper.findDraftVideo(task, "default")).isEmpty();
        assertThat(mapper.findDraftVideoByVersion(task, "default", old.versionId())).isPresent();
        assertThat(mapper.findDraftVideoByVersion(task, "someone-else", old.versionId())).isEmpty();
    }

    @Test
    void newUploadMustKeepOldVersionAndFailedUploadMustNotReplaceCurrent() {
        String task = task();
        var first = save(task, "first");
        assertThat(mapper.findDraftVideo(task, "default")).isEmpty();
        complete(first);
        var second = save(task, "second");
        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionId()).isEqualTo(first.versionId());
        persistence.markUploadAborted(second);
        var third = save(task, "third");
        complete(third);
        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionNo()).isEqualTo(3);
        assertThat(mapper.findDraftVideoByVersion(task, "default", first.versionId()).orElseThrow().status()).isEqualTo("UPLOADED");
        assertThat(mapper.findDraftVideoByVersion(task, "default", second.versionId()).orElseThrow().status()).isEqualTo("UPLOAD_ABORTED");
        var fourth = save(task, "fourth-fails");
        mapper.markUploadUploading(task, "default", fourth.uploadSessionId());
        mapper.markUploadVerifying(task, "default", fourth.uploadSessionId());
        persistence.markUploadFailed(fourth, "隔离测试中的对象校验失败");
        assertThat(mapper.findDraftVideoByVersion(task, "default", fourth.versionId()).orElseThrow().status()).isEqualTo("UPLOAD_FAILED");
        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionId()).isEqualTo(third.versionId());

        // 完成旧会话的重放只能返回旧版，不能再切回旧指针，也不能访问或删除存储对象。
        var storage = mock(ObjectStorageService.class);
        var service = new MediaUploadService(new CreatorMediaProperties(), new ObjectStorageProperties(), storage,
                mapper, persistence, mock(CreatorMediaWorkflowGateService.class));
        assertThat(service.completeUpload("default", task, first.uploadSessionId()).versionId()).isEqualTo(first.versionId());
        persistence.markUploadCompleted(first, "video/mp4", 1024L, LocalDateTime.now());
        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionId()).isEqualTo(third.versionId());
        jdbc.update("UPDATE creator_task SET planning_skipped = 1 WHERE task_id = ?", task);
        assertThat(gate().inspectPostPublishReadiness(task, "default", "反馈").ready()).isFalse();
        verifyNoInteractions(storage);
    }

    @Test
    void concurrentSameKeyMustPersistOneVersionAndReturnOneSession() throws Exception {
        String task = task();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    var candidate = upload(task, UUID.randomUUID().toString(), "same-key");
                    start.await();
                    return persistence.saveCreatedUpload(draft(candidate, 0), candidate, revision(task)).uploadSessionId();
                }));
            }
            start.countDown();
            var ids = new ArrayList<String>();
            for (var future : futures) ids.add(future.get(20, TimeUnit.SECONDS));
            assertThat(ids.stream().distinct().count()).isEqualTo(1);
        }
        assertThat(count("creator_draft_video", task)).isEqualTo(1);
        assertThat(count("creator_media_upload", task)).isEqualTo(1);
        assertThat(mapper.findMediaWorkspace(task, "default").orElseThrow().nextVersionNo()).isEqualTo(2);
    }

    @Test
    void competingUploadsMustNotCreateTwoActiveSessions() throws Exception {
        String task = task();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try { save(task, UUID.randomUUID().toString()); return true; }
                    catch (ResponseStatusException exception) {
                        assertThat(exception.getStatusCode().value()).isEqualTo(409);
                        return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
        }
        assertThat(count("creator_draft_video", task)).isEqualTo(1);
    }

    @Test
    void allocationMustRollBackWhenUploadInsertFails() {
        var first = save(task(), "duplicate-session");
        String other = task();
        var conflicting = upload(other, first.uploadSessionId(), "different-key");
        assertThatThrownBy(() -> persistence.saveCreatedUpload(draft(conflicting, 0), conflicting, revision(other)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("creator_draft_video", other)).isZero();
        assertThat(mapper.findMediaWorkspace(other, "default")).isEmpty();
        var recovered = save(other, "recovered");
        assertThat(mapper.findDraftVideoByVersion(other, "default", recovered.versionId()).orElseThrow().versionNo()).isEqualTo(1);
    }

    @Test
    void adoptionMustNotReuseSoftDeletedVersionNumbersAndStaleRevisionMustFail() {
        String task = task();
        var old = draft(upload(task, "deleted-history", "old"), 7);
        mapper.insertDraftVideo(old);
        jdbc.update("UPDATE creator_draft_video SET is_deleted = 1 WHERE version_id = ?", old.versionId());
        var value = save(task, "after-deletion");
        assertThat(mapper.findDraftVideoByVersion(task, "default", value.versionId()).orElseThrow().versionNo()).isEqualTo(8);
        complete(value);
        assertThat(mapper.activateUploadedVersion(task, "default", value.versionId(), 0)).isZero();
    }

    @Test
    void bindingMustRejectActiveUploadAndSerializeWithNewUpload() throws Exception {
        String task = task();
        var value = save(task, "before-binding");
        var gate = gate();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> gate.lockForVideoBinding(task, "default")))
                .isInstanceOf(ResponseStatusException.class);
        complete(value);
        var locked = new CountDownLatch(1);
        var contenderStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var binding = pool.submit(() -> transactions.executeWithoutResult(status -> {
                gate.lockForVideoBinding(task, "default");
                locked.countDown();
                try { assertThat(contenderStarted.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { throw new RuntimeException(exception); }
                jdbc.update("INSERT INTO creator_task_video_binding (binding_id, task_id, user_id, bvid, binding_status) VALUES (?, ?, 'default', 'BV1234567890', 'WAITING_VERIFY')",
                        UUID.randomUUID().toString(), task);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var upload = pool.submit(() -> {
                contenderStarted.countDown();
                assertThatThrownBy(() -> save(task, "after-binding")).isInstanceOf(ResponseStatusException.class);
            });
            binding.get(15, TimeUnit.SECONDS);
            upload.get(15, TimeUnit.SECONDS);
        }
        assertThat(count("creator_draft_video", task)).isEqualTo(1);
        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionId()).isEqualTo(value.versionId());
    }

    @Test
    void sameKeyDifferentFileMustBeRejectedWithoutAllocatingVersion() {
        String task = task();
        save(task, "key");
        var original = upload(task, "second-session", "key");
        var changed = new MediaUploadRecord(null, original.uploadSessionId(), original.versionId(), task, "default",
                original.storageUploadId(), original.objectKey(), "video/mp4", 1024L, "b".repeat(64),
                1024, 1, "CREATED", "key", null, original.expiresAt(), null, null, null);
        assertThatThrownBy(() -> persistence.saveCreatedUpload(draft(changed, 0), changed, revision(task))).isInstanceOf(ResponseStatusException.class);
        assertThat(count("creator_draft_video", task)).isEqualTo(1);
    }

    @Test
    void explicitPublishedFlagMustAlsoPreventReplacingPublishedWork() {
        String task = task();
        var value = save(task, "published");
        complete(value);
        jdbc.update("UPDATE creator_draft_video SET published_flag = 1 WHERE version_id = ?", value.versionId());
        assertThatThrownBy(() -> save(task, "new-file")).isInstanceOf(ResponseStatusException.class);
        assertThat(count("creator_draft_video", task)).isEqualTo(1);
        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionId()).isEqualTo(value.versionId());
    }

    @Test
    void repositionMustPreserveCompletedHistoryAndFenceLateOldWork() {
        String task = task();
        var old = save(task, "old-complete");
        complete(old);
        jdbc.update("UPDATE creator_draft_video SET status = 'READY_FOR_REVIEW', duration_ms = 45000, width = 1920, height = 1080, current_review_id = 'old-reference' WHERE version_id = ?", old.versionId());
        String completedJob = processingJob(old, "COMPLETED");
        String completedReview = review(old, completedJob, "COMPLETED");
        String runningJob = processingJob(old, "RUNNING");
        String runningReview = review(old, completedJob, "RUNNING");
        var pending = save(task, "unfinished-old-upload");
        mapper.markUploadUploading(task, "default", pending.uploadSessionId());
        mapper.markUploadVerifying(task, "default", pending.uploadSessionId());
        long previousRevision = revision(task);

        production.startGenerating(plan(task, UUID.randomUUID().toString()));

        var preserved = mapper.findDraftVideoByVersion(task, "default", old.versionId()).orElseThrow();
        assertThat(preserved.status()).isEqualTo("READY_FOR_REVIEW");
        assertThat(preserved.durationMs()).isEqualTo(45000L);
        assertThat(preserved.width()).isEqualTo(1920);
        assertThat(preserved.objectKey()).isEqualTo(old.objectKey());
        assertThat(jdbc.queryForObject("SELECT current_review_id FROM creator_draft_video WHERE version_id = ?", String.class, old.versionId())).isEqualTo("old-reference");
        assertThat(state("creator_media_processing_job", "job_id", completedJob)).isEqualTo("COMPLETED");
        assertThat(state("creator_preflight_review", "review_id", completedReview)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT executive_summary FROM creator_preflight_review WHERE review_id = ? AND is_deleted = 0", String.class, completedReview)).isEqualTo("隔离测试中的原始试映结论");
        assertThat(state("creator_media_processing_job", "job_id", runningJob)).isEqualTo("FAILED");
        assertThat(state("creator_preflight_review", "review_id", runningReview)).isEqualTo("CANCELLED");
        assertThat(mapper.findUpload(task, "default", pending.uploadSessionId()).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(mapper.findUpload(task, "default", old.uploadSessionId()).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(mapper.findDraftVideo(task, "default")).isEmpty();
        assertThat(revision(task)).isEqualTo(previousRevision + 1);
        assertThat(processing.completeJob(runningJob, "test-worker", "{}")).isZero();
        assertThat(reviews.completeReview(runningReview, "test-worker", "迟到结果", null)).isZero();
        assertThatThrownBy(() -> persistence.markUploadCompleted(pending, "video/mp4", 1024L, LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(mapper.findDraftVideo(task, "default")).isEmpty();

        var next = save(task, "new-direction");
        complete(next);
        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionNo()).isEqualTo(3);
        assertThat(state("creator_preflight_review", "review_id", completedReview)).isEqualTo("COMPLETED");
    }

    @Test
    void failedPlanInsertMustRollBackPointerCancellationAndPendingBvDeletion() {
        String duplicateId = UUID.randomUUID().toString();
        production.startGenerating(plan(task(), duplicateId));
        String task = task();
        var old = save(task, "original");
        complete(old);
        String job = processingJob(old, "COMPLETED");
        String report = review(old, job, "COMPLETED");
        jdbc.update("INSERT INTO creator_task_video_binding (binding_id, task_id, user_id, bvid, binding_status) VALUES (?, ?, 'default', 'BV1234567890', 'WAITING_VERIFY')", UUID.randomUUID().toString(), task);
        long previousRevision = revision(task);

        assertThatThrownBy(() -> production.startGenerating(plan(task, duplicateId)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionId()).isEqualTo(old.versionId());
        assertThat(revision(task)).isEqualTo(previousRevision);
        assertThat(mapper.countPublishingFacts(task, "default")).isEqualTo(1);
        assertThat(state("creator_preflight_review", "review_id", report)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_production_plan WHERE task_id = ?", Integer.class, task)).isZero();
    }

    @Test
    void uploadStartedBeforeRepositionMustNotBeAdoptedAfterNewPlanIsReady() {
        String task = task();
        var old = save(task, "original");
        complete(old);
        var storage = mock(ObjectStorageService.class);
        String newPlan = UUID.randomUUID().toString();
        when(storage.createMultipartUpload(anyString(), anyString())).thenAnswer(call -> {
            production.startGenerating(plan(task, newPlan));
            jdbc.update("UPDATE creator_production_plan SET status = 'READY' WHERE plan_id = ?", newPlan);
            return new com.link.linkagent.creator.media.storage.MultipartUploadHandle("late-storage-upload");
        });
        var properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        var storageProperties = new ObjectStorageProperties();
        storageProperties.setBucket("isolated-test");
        storageProperties.setAccessKey("test-key");
        storageProperties.setSecretKey("test-secret");
        var service = new MediaUploadService(properties, storageProperties, storage, mapper, persistence,
                mock(CreatorMediaWorkflowGateService.class));

        var failure = catchThrowableOfType(() -> service.createUpload("default", task, "late-request",
                new com.link.linkagent.creator.media.upload.model.CreateMediaUploadRequest("旧方向请求", "old.mp4", 1024L, "video/mp4", 1L)), ResponseStatusException.class);

        assertThat(failure.getStatusCode().value()).isEqualTo(409);
        assertThat(failure.getReason()).contains("上下文已经变化");
        assertThat(count("creator_draft_video", task)).isEqualTo(1);
        assertThat(mapper.findDraftVideo(task, "default")).isEmpty();
        verify(storage).abortMultipartUpload(eq("isolated-test"), anyString(), eq("late-storage-upload"));
        verify(storage, never()).deleteObject(anyString(), anyString());
    }

    @Test
    void repositionMustRecheckPublicationInsideTaskLock() throws Exception {
        String task = task();
        var old = save(task, "original");
        complete(old);
        var locked = new CountDownLatch(1);
        var attempted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var binding = pool.submit(() -> transactions.executeWithoutResult(status -> {
                mapper.lockMediaTask(task, "default");
                locked.countDown();
                try { assertThat(attempted.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { throw new RuntimeException(exception); }
                jdbc.update("INSERT INTO creator_task_video_binding (binding_id, task_id, user_id, bvid, binding_status) VALUES (?, ?, 'default', 'BV1234567890', 'BOUND')", UUID.randomUUID().toString(), task);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var reposition = pool.submit(() -> {
                attempted.countDown();
                var failure = catchThrowableOfType(() -> production.startGenerating(plan(task, UUID.randomUUID().toString())), ResponseStatusException.class);
                assertThat(failure.getStatusCode().value()).isEqualTo(409);
            });
            binding.get(15, TimeUnit.SECONDS);
            reposition.get(15, TimeUnit.SECONDS);
        }
        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionId()).isEqualTo(old.versionId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_production_plan WHERE task_id = ?", Integer.class, task)).isZero();
        String flaggedTask = task();
        var flagged = save(flaggedTask, "published-flag");
        complete(flagged);
        jdbc.update("UPDATE creator_draft_video SET published_flag = 1 WHERE version_id = ?", flagged.versionId());
        assertThatThrownBy(() -> production.startGenerating(plan(flaggedTask, UUID.randomUUID().toString())))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(mapper.findDraftVideo(flaggedTask, "default").orElseThrow().versionId()).isEqualTo(flagged.versionId());
    }

    @Test
    void legacyProbeMustLoseItsClaimAfterRepositionWithoutFallbackToV1() {
        String task = task();
        var value = upload(task, "legacy-probe", "legacy-probe");
        mapper.insertDraftVideo(draft(value, 1));
        jdbc.update("UPDATE creator_draft_video SET status = 'UPLOADED' WHERE version_id = ?", value.versionId());
        assertThat(mapper.claimDraftVideoProbe(task, "default", value.versionId(), "old-probe")).isEqualTo(1);
        production.startGenerating(plan(task, UUID.randomUUID().toString()));
        assertThat(mapper.findDraftVideo(task, "default")).isEmpty();
        var old = mapper.findDraftVideoByVersion(task, "default", value.versionId()).orElseThrow();
        assertThat(old.status()).isEqualTo("PROBE_FAILED");
        assertThat(old.probeAttemptId()).isNull();
        assertThat(mapper.updateDraftVideoProbeResult(task, "default", value.versionId(), "PROBING", "old-probe",
                "READY_FOR_REVIEW", 45000L, 1920, 1080, java.math.BigDecimal.valueOf(24), "h264", "aac", true)).isZero();
        assertThat(mapper.findMediaWorkspace(task, "default").orElseThrow().nextVersionNo()).isEqualTo(2);
    }

    @Test
    void explicitBindingAndDelayedSyncMustKeepExactPublishedVersionAcrossPointerChanges() {
        String task = task();
        var old = readyVideo(task, "old");
        var selected = readyVideo(task, "selected");
        var response = bindingService.bindBvToTask(task, new BindBvRequest("default", "27058248", "BV1234567890"));
        assertThat(response.publishedVersionId()).isEqualTo(selected.versionId());
        assertThat(response.bindingStatus()).isEqualTo("WAITING_VERIFY");
        var pending = bilibili.findBindingByTaskId(task).orElseThrow();
        assertThat(bilibili.findBindingsByBvid(pending.getBvid())).extracting(TaskVideoBindingRecord::getPublishedVersionId)
                .contains(selected.versionId());
        assertThat(bilibili.listBindingsByUid("27058248")).extracting(TaskVideoBindingRecord::getPublishedVersionId)
                .contains(selected.versionId());
        assertThat(bilibili.listBindingsByUserId("default")).extracting(TaskVideoBindingRecord::getPublishedVersionId)
                .contains(selected.versionId());

        // 只在隔离数据中模拟指针变化，验证门禁没有依赖“当前指针不会变”的偶然前提。
        jdbc.update("UPDATE creator_creation_workspace SET current_video_version_id = ? WHERE task_id = ?", old.versionId(), task);
        assertThat(syncing.persist(account(), verification(pending.getBvid(), "FOUND"), List.of(pending)).linkedCount()).isEqualTo(1);
        assertThat(mapper.findPublishedVideoVersionId(task, "default")).contains(selected.versionId());
        readyGate.ensureReadyForPostPublish(task, "default", "观众反馈");
        jdbc.update("UPDATE creator_draft_video SET status = 'PROBE_FAILED' WHERE version_id = ?", selected.versionId());
        assertThat(readyGate.inspectPostPublishReadiness(task, "default", "观众反馈").ready()).isFalse();
        assertThatThrownBy(() -> readyGate.ensureReadyForPostPublish(task, "default", "观众反馈"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void delayedSyncMustNotPromoteUnreadyVersionUsingAnotherReadyVideo() {
        String task = task();
        var previous = readyVideo(task, "previous");
        var selected = readyVideo(task, "selected");
        bindingService.bindBvToTask(task, new BindBvRequest("default", "27058248", "BV1234567890"));
        var pending = bilibili.findBindingByTaskId(task).orElseThrow();
        jdbc.update("UPDATE creator_draft_video SET status = 'PROBE_FAILED' WHERE version_id = ?", selected.versionId());
        jdbc.update("UPDATE creator_creation_workspace SET current_video_version_id = ? WHERE task_id = ?", previous.versionId(), task);
        var response = syncing.persist(account(), verification(pending.getBvid(), "FOUND"), List.of(pending));
        assertThat(response.linkedCount()).isZero();
        assertThat(bilibili.findBindingByTaskId(task).orElseThrow().getBindingStatus()).isEqualTo("WAITING_VERIFY");
    }

    @Test
    void legacyUnknownVersionMustRemainUnknownUntilUserExplicitlyConfirmsBinding() {
        String task = task();
        var selected = readyVideo(task, "legacy");
        jdbc.update("INSERT INTO creator_task_video_binding (binding_id, task_id, user_id, bilibili_uid, bvid) VALUES (?, ?, 'default', '27058248', 'BV1234567890')",
                UUID.randomUUID().toString(), task);
        var legacy = bilibili.findBindingByTaskId(task).orElseThrow();
        assertThat(syncing.persist(account(), verification(legacy.getBvid(), "FOUND"), List.of(legacy)).linkedCount()).isZero();
        assertThat(bilibili.findBindingByTaskId(task).orElseThrow().getPublishedVersionId()).isNull();
        var explicit = bindingService.bindBvToTask(task, new BindBvRequest("default", "27058248", "BV1234567890"));
        assertThat(explicit.publishedVersionId()).isEqualTo(selected.versionId());
        // 已经正式绑定的历史记录只能原样返回，不能用现在的成片反推过去。
        jdbc.update("UPDATE creator_task_video_binding SET binding_status = 'BOUND', published_version_id = NULL WHERE task_id = ?", task);
        var repeated = bindingService.bindBvToTask(task, new BindBvRequest("default", "27058248", "BVabcdefghij"));
        assertThat(repeated.bvid()).isEqualTo("BV1234567890");
        assertThat(repeated.publishedVersionId()).isNull();
    }

    @Test
    void oldSyncResultsMustNotOverwriteRepairedBvOrItsVersion() {
        String task = task();
        var selected = readyVideo(task, "repair");
        bindingService.bindBvToTask(task, new BindBvRequest("default", "27058248", "BV1234567890"));
        var oldBinding = bilibili.findBindingByTaskId(task).orElseThrow();
        var response = transactions.execute(status -> {
            // 先建立旧的可重复读快照，再让另一个事务修正 BV，验证同步用的是当前读。
            assertThat(bilibili.findBindingByTaskId(task).orElseThrow().getBvid()).isEqualTo(oldBinding.getBvid());
            try (var pool = Executors.newSingleThreadExecutor()) {
                try {
                    pool.submit(() -> bindingService.bindBvToTask(task,
                            new BindBvRequest("default", "27058248", "BVabcdefghij"))).get(15, TimeUnit.SECONDS);
                } catch (Exception exception) { throw new RuntimeException(exception); }
            }
            return syncing.persist(account(), verification(oldBinding.getBvid(), "UID_MISMATCH"), List.of(oldBinding));
        });
        assertThat(response.anomalyCount()).isZero();
        assertThat(response.linkedCount()).isZero();
        var saved = bilibili.findBindingByTaskId(task).orElseThrow();
        assertThat(saved.getBvid()).isEqualTo("BVabcdefghij");
        assertThat(saved.getBindingStatus()).isEqualTo("WAITING_VERIFY");
        assertThat(saved.getPublishedVersionId()).isEqualTo(selected.versionId());
        assertThat(response.warnings()).anyMatch(value -> value.contains("绑定已变化"));
    }

    @Test
    void syncMustWaitForRepositionAndMustNotReviveRemovedBinding() throws Exception {
        String task = task();
        readyVideo(task, "before-reposition");
        bindingService.bindBvToTask(task, new BindBvRequest("default", "27058248", "BV1234567890"));
        var oldBinding = bilibili.findBindingByTaskId(task).orElseThrow();
        var locked = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reposition = pool.submit(() -> transactions.executeWithoutResult(status -> {
                mapper.lockMediaTask(task, "default");
                locked.countDown();
                try { assertThat(started.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { throw new RuntimeException(exception); }
                production.startGenerating(plan(task, UUID.randomUUID().toString()));
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var sync = pool.submit(() -> {
                started.countDown();
                return syncing.persist(account(), verification(oldBinding.getBvid(), "FOUND"), List.of(oldBinding));
            });
            reposition.get(15, TimeUnit.SECONDS);
            assertThat(sync.get(15, TimeUnit.SECONDS).linkedCount()).isZero();
        }
        assertThat(bilibili.findBindingByTaskId(task)).isEmpty();
        assertThat(mapper.findDraftVideo(task, "default")).isEmpty();
    }

    @Test
    void publishedVersionOutsideTaskMustNotFallBackAndMustPreserveOwnership() {
        String task = task();
        readyVideo(task, "own");
        var foreign = readyVideo(task(), "foreign");
        jdbc.update("INSERT INTO creator_task_video_binding (binding_id, task_id, user_id, bilibili_uid, bvid, binding_status, published_version_id) VALUES (?, ?, 'default', '27058248', 'BV1234567890', 'BOUND', ?)",
                UUID.randomUUID().toString(), task, foreign.versionId());
        assertThat(readyGate.inspectPostPublishReadiness(task, "default", "观众反馈").ready()).isFalse();
        assertThat(mapper.findPublishedVideoVersionId(task, "someone-else")).isEmpty();
        assertThatThrownBy(() -> readyGate.ensureReadyForPostPublish(task, "default", "观众反馈"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void deletingPublishedMediaMustPreserveExactVersionAndFeedbackReadiness() {
        String task = task();
        var selected = readyVideo(task, "published-media-delete");
        bindingService.bindBvToTask(task, new BindBvRequest("default", "27058248", "BV1234567890"));
        var pending = bilibili.findBindingByTaskId(task).orElseThrow();
        syncing.persist(account(), verification(pending.getBvid(), "FOUND"), List.of(pending));
        var storage = mock(ObjectStorageService.class);
        transactions.executeWithoutResult(status ->
                new MediaDeletionService(mapper, processing, reviews, storage).deleteMedia("default", task, selected.versionId()));

        assertThat(mapper.findDraftVideoByVersion(task, "default", selected.versionId()).orElseThrow().mediaDeletedAt()).isNotNull();
        assertThat(mapper.findPublishedVideoVersionId(task, "default")).contains(selected.versionId());
        assertThat(reviews.findCurrentByVersion(task, "default", selected.versionId()).orElseThrow().status()).isEqualTo("COMPLETED");
        readyGate.ensureReadyForPostPublish(task, "default", "观众反馈");
        assertThat(readyGate.inspectPostPublishReadiness(task, "default", "观众反馈").ready()).isTrue();
        verify(storage).deleteObject("isolated-test", selected.objectKey());
    }

    @Test
    void versionQueriesMustNotCreateWorkspaceOrExposeStorageAndUnknownContextCannotBeSelected() {
        String task = task();
        var legacy = draft(upload(task, "unknown-context", "unknown-context"), 1);
        mapper.insertDraftVideo(legacy);
        jdbc.update("UPDATE creator_draft_video SET status = 'READY_FOR_REVIEW' WHERE version_id = ?", legacy.versionId());
        var service = workspace(mockStorage());
        assertThat(service.snapshot(task, "default").enabled()).isFalse();
        assertThat(mapper.findMediaWorkspace(task, "default")).isEmpty();
        var page = service.list(task, "default", 1, 20);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.versions().getFirst().selected()).isTrue();
        assertThat(page.versions().getFirst().contextAvailable()).isFalse();
        assertThatThrownBy(() -> service.select(task, "default", legacy.versionId(), 0))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("未保存可靠制作上下文");
        assertThatThrownBy(() -> service.list(task, "another-owner", 1, 20)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.list(task, "default", 1, 101)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void regularVersionContextMustRemainReadyWhenNewPlanAndConfirmationArePending() {
        String task = task();
        String planId = confirmedPlan(task);
        var old = readyVideo(task, "regular-context");
        var context = mapper.findVersionContext(task, "default", old.versionId()).orElseThrow();
        assertThat(context.planningSkipped()).isFalse();
        assertThat(context.productionPlanId()).isEqualTo(planId);
        assertThat(context.snapshotJson()).contains("原始素材", "确认的方案");
        var newer = readyVideo(task, "second-context");
        production.startGenerating(plan(task, UUID.randomUUID().toString(), 2));
        jdbc.update("UPDATE creator_workflow_session SET status = 'WAITING_CONFIRMATION' WHERE task_id = ?", task);
        jdbc.update("UPDATE creator_material SET content = '新候选材料' WHERE task_id = ?", task);
        jdbc.update("UPDATE creator_task SET planning_skipped = 0 WHERE task_id = ?", task);
        var response = workspace(mockStorage()).select(task, "default", old.versionId(), revision(task));
        assertThat(response.currentVideoVersionId()).isEqualTo(old.versionId());
        assertThat(response.versionContext().toString()).contains("原始素材").doesNotContain("新候选材料");
        readyGate.ensureReadyForPostPublish(task, "default", "观众反馈");
        assertThat(mapper.findVersionContext(task, "default", newer.versionId())).isPresent();
    }

    @Test
    void invalidConfirmationOrIncompletePlanMustRollbackUploadAndVersionAllocation() {
        String task = task();
        jdbc.update("UPDATE creator_task SET planning_skipped = 0 WHERE task_id = ?", task);
        assertThatThrownBy(() -> save(task, "no-confirmation")).isInstanceOf(ResponseStatusException.class);
        assertThat(count("creator_draft_video", task)).isZero();
        assertThat(mapper.findMediaWorkspace(task, "default")).isEmpty();
        String planId = confirmedPlan(task);
        jdbc.update("INSERT INTO creator_production_step (step_id, plan_id, task_id, sequence_no, phase, step_name, objective, status) VALUES (?, ?, ?, 1, 'SCRIPT', '未完成', '真实检查', 'PENDING')",
                UUID.randomUUID().toString(), planId, task);
        assertThatThrownBy(() -> save(task, "incomplete-plan")).isInstanceOf(ResponseStatusException.class);
        assertThat(count("creator_draft_video", task)).isZero();
        assertThat(count("creator_video_version_context", task)).isZero();
        jdbc.update("UPDATE creator_production_step SET status = 'COMPLETED' WHERE task_id = ?", task);
        var value = save(task, "complete-plan");
        assertThat(mapper.findDraftVideoByVersion(task, "default", value.versionId()).orElseThrow().versionNo()).isEqualTo(1);
    }

    @Test
    void existingVideoSkipMustBeFrozenPerVersionInsteadOfReadingCurrentTaskFlag() {
        String task = task();
        var value = readyVideo(task, "skip-on-upload");
        jdbc.update("UPDATE creator_task SET planning_skipped = 0 WHERE task_id = ?", task);
        assertThat(mapper.findVersionContext(task, "default", value.versionId()).orElseThrow().planningSkipped()).isTrue();
        readyGate.ensureVersionReady(task, "default", value.versionId(), "历史版本");
        assertThatThrownBy(() -> readyGate.ensurePrePublishConfirmed(task, "default", "新上传"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void concurrentSelectionsWithSameRevisionMustHaveOneWinner() throws Exception {
        String task = task();
        var first = readyVideo(task, "first");
        var second = readyVideo(task, "second");
        readyVideo(task, "third");
        long expected = revision(task);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<Boolean>>();
            for (String id : List.of(first.versionId(), second.versionId())) {
                results.add(pool.submit(() -> {
                    start.await();
                    try { workspace(mockStorage()).select(task, "default", id, expected); return true; }
                    catch (ResponseStatusException exception) {
                        assertThat(exception.getStatusCode().value()).isEqualTo(409);
                        return false;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (var result : results) if (result.get(15, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(revision(task)).isEqualTo(expected + 1);
        assertThat(count("creator_draft_video", task)).isEqualTo(3);
    }

    @Test
    void selectionMustRejectActiveUploadsAndPreflightWithoutReadingStorage() {
        String task = task();
        var old = readyVideo(task, "ready");
        var active = save(task, "active");
        var storage = mockStorage();
        assertThatThrownBy(() -> workspace(storage).select(task, "default", old.versionId(), revision(task)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("仍有上传");
        verifyNoInteractions(storage);
        persistence.markUploadAborted(active);
        review(old, processingJob(old, "COMPLETED"), "RUNNING");
        assertThatThrownBy(() -> workspace(storage).select(task, "default", old.versionId(), revision(task)))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void repositionOrDeletionDuringStorageHeadMustNotReviveOldVersion() {
        String task = task();
        var old = readyVideo(task, "head-race");
        long expected = revision(task);
        var storage = mockStorage();
        when(storage.headObject(anyString(), anyString())).thenAnswer(call -> {
            production.startGenerating(plan(task, UUID.randomUUID().toString()));
            return new StoredObjectMetadata(1024, "video/mp4", "etag");
        });
        assertThatThrownBy(() -> workspace(storage).select(task, "default", old.versionId(), expected))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("工作区已变化");
        assertThat(mapper.findDraftVideo(task, "default")).isEmpty();
        var deletingStorage = mockStorage();
        when(deletingStorage.headObject(anyString(), anyString())).thenAnswer(call -> {
            mapper.markDraftMediaDeleted(task, "default", old.versionId());
            return new StoredObjectMetadata(1024, "video/mp4", "etag");
        });
        assertThatThrownBy(() -> workspace(deletingStorage).select(task, "default", old.versionId(), revision(task)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("媒体已删除");
        assertThat(mapper.findDraftVideo(task, "default")).isEmpty();
    }

    @Test
    void unavailableOrChangedStorageMustNotChangeCurrentPointer() {
        String task = task();
        var old = readyVideo(task, "old");
        var current = readyVideo(task, "new");
        long before = revision(task);
        var storage = mockStorage();
        when(storage.headObject(anyString(), anyString())).thenThrow(new MediaStorageException("不可用", null));
        assertThatThrownBy(() -> workspace(storage).select(task, "default", old.versionId(), before))
                .isInstanceOf(ResponseStatusException.class);
        doReturn(new StoredObjectMetadata(999, "video/mp4", "etag")).when(storage).headObject(anyString(), anyString());
        assertThatThrownBy(() -> workspace(storage).select(task, "default", old.versionId(), before))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionId()).isEqualTo(current.versionId());
        assertThat(revision(task)).isEqualTo(before);
    }

    @Test
    void bindingSnapshotMustFreezePublishedCopyWhileMetricsAndEditorSelectionChange() {
        String task = task();
        var previous = readyVideo(task, "previous");
        var published = readyVideo(task, "published");
        String bvid = "BVfrozen0001";
        var video = new BilibiliVideoRecord();
        video.setVideoId(UUID.randomUUID().toString()); video.setBilibiliUid("27058248"); video.setBvid(bvid);
        video.setSyncStatus("SYNCED"); video.setTitle("原始正式标题");
        video.setRawSnapshot("{\"desc\":\"原始正式简介\",\"stat\":{\"reply\":1}}");
        video.setLastSyncTime(LocalDateTime.now()); video.setViewCount(100L);
        bilibili.insertVideo(video);
        bindingService.bindBvToTask(task, new BindBvRequest("default", "27058248", bvid));
        var binding = bilibili.findBindingByTaskId(task).orElseThrow();
        String frozen = binding.getPublishedSnapshotJson();
        assertThat(frozen).contains("原始正式标题", "原始正式简介", published.versionId());
        video.setTitle("后来缓存标题"); video.setRawSnapshot("{\"desc\":\"后来缓存简介\",\"stat\":{\"reply\":2}}");
        video.setViewCount(222L); bilibili.insertVideo(video);
        syncing.persist(account(), verification(bvid, "FOUND"), List.of(binding));
        workspace(mockStorage()).select(task, "default", previous.versionId(), revision(task));
        assertThat(bilibili.findBindingByTaskId(task).orElseThrow().getPublishedSnapshotJson()).isEqualTo(frozen);
        assertThat(mapper.findPublishedVideoVersionId(task, "default")).contains(published.versionId());
        var owner = new com.link.linkagent.creator.task.model.CreatorTaskRecord(); owner.setTaskId(task); owner.setUserId("default");
        String prompt = new CreatorPublishedVideoContextService(bilibili, new ObjectMapper()).buildPrompt(owner);
        assertThat(prompt).contains("原始正式标题", "原始正式简介", "播放量：222", "平台评论总量：2", published.versionId())
                .doesNotContain("后来缓存标题", "后来缓存简介");
    }

    @Test
    void bindingAndSnapshotMustRollbackTogether() {
        String task = task();
        readyVideo(task, "rollback");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            bindingService.bindBvToTask(task, new BindBvRequest("default", "27058248", "BVfrozen0001"));
            throw new IllegalStateException("模拟后续数据库失败");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(bilibili.findBindingByTaskId(task)).isEmpty();
    }

    @Test
    void delayedVerificationMustFreezeRealPublicMetadataWithoutGuessingMissingFields() {
        String task = task();
        var selected = readyVideo(task, "delayed-copy");
        String bvid = "BVdelay00001";
        bindingService.bindBvToTask(task, new BindBvRequest("default", "27058248", bvid));
        var binding = bilibili.findBindingByTaskId(task).orElseThrow();
        assertThat(binding.getPublishedSnapshotJson()).isNull();
        var item = new com.link.linkagent.creator.bilibili.model.BilibiliVideoSyncItem(bvid, null,
                "延迟核验的真实标题", null, null, 123L, null, null, null, null, "27058248",
                "{\"desc\":null,\"description\":\"列表简介\",\"typeid\":171,\"tags\":null,\"tag\":[\"测试\"],\"owner\":{\"mid\":null}}");
        syncing.persist(account(), new BilibiliVideoSyncPayload("27058248", null, null, false, false,
                List.of(item), List.of(), List.of()), List.of(binding));
        var saved = bilibili.findBindingByTaskId(task).orElseThrow();
        assertThat(saved.getBindingStatus()).isEqualTo("BOUND");
        assertThat(saved.getPublishedSnapshotJson()).contains("延迟核验的真实标题", selected.versionId());
        var owner = new com.link.linkagent.creator.task.model.CreatorTaskRecord(); owner.setTaskId(task); owner.setUserId("default");
        assertThat(new CreatorPublishedVideoContextService(bilibili, new ObjectMapper()).buildPrompt(owner))
                .contains("正式简介：列表简介", "分区 ID：171", "标签：测试");
    }

    @Test
    void nullableSnapshotUpgradeMustPreserveOldRowsAndBeRepeatable() throws Exception {
        String table = "binding_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String sql;
        try (var stream = MediaVersionPersistenceIntegrationTest.class.getClassLoader().getResourceAsStream("sql/init.sql")) {
            sql = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int start = sql.indexOf("SET @binding_snapshot_ddl");
        String end = "DEALLOCATE PREPARE binding_snapshot_stmt;";
        String block = sql.substring(start, sql.indexOf(end, start) + end.length()).replace("creator_task_video_binding", table);
        jdbc.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, published_version_id VARCHAR(64))");
        try {
            jdbc.update("INSERT INTO " + table + " (id) VALUES (1)");
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                try (var statement = connection.createStatement()) {
                    for (int attempt = 0; attempt < 2; attempt++) {
                        for (String line : block.split("\\R")) if (!line.isBlank()) statement.execute(line.trim());
                    }
                }
                return null;
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = 1 AND published_snapshot_json IS NULL AND published_version_id IS NULL", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TABLE " + table);
        }
    }

    @Test
    void negativeSyncMustNotErasePublishedFactOrAllowReplacingPublishedBinding() {
        String task = task();
        var previous = readyVideo(task, "unpublished-previous");
        var version = readyVideo(task, "permanent-publication");
        String bvid = "BVpersist001";
        bindingService.bindBvToTask(task, new BindBvRequest("default", "27058248", bvid));
        var pending = bilibili.findBindingByTaskId(task).orElseThrow();
        syncing.persist(account(), verification(bvid, "FOUND"), List.of(pending));
        var bound = bilibili.findBindingByTaskId(task).orElseThrow();
        assertThat(bilibili.countPermanentPublishingFact(bound.getBindingId())).isEqualTo(1);
        syncing.persist(account(), verification(bvid, "UID_MISMATCH"), List.of(bound));
        assertThat(bilibili.findBindingByTaskId(task).orElseThrow().getBindingStatus()).isEqualTo("UID_MISMATCH");
        workspace(mockStorage()).select(task, "default", previous.versionId(), revision(task));
        assertThat(mapper.findPublishedVideoVersionId(task, "default")).contains(version.versionId());
        readyGate.ensureReadyForPostPublish(task, "default", "历史反馈");
        jdbc.update("UPDATE creator_draft_video SET status = 'PROBE_FAILED' WHERE version_id = ?", version.versionId());
        assertThatThrownBy(() -> readyGate.ensureReadyForPostPublish(task, "default", "历史反馈"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> production.startGenerating(plan(task, UUID.randomUUID().toString())))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("已经发布");
        var response = bindingService.bindBvToTask(task, new BindBvRequest("default", "27058248", "BVother00001"));
        assertThat(response.bvid()).isEqualTo(bvid);
        assertThat(response.publishedVersionId()).isEqualTo(version.versionId());
        assertThat(response.bindingStatus()).isEqualTo("UID_MISMATCH");
    }

    @Test void generatedDraftRegistersIndependentlyAndDoesNotCompleteTheRun() {
        String task = task(); var old = readyVideo(task, "before-generated");
        var lease = fullLease(task, fullInput(task, "c".repeat(64)));
        var attempt = prepareGenerated(lease, "attempt-one");
        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionId()).isEqualTo(old.versionId());
        assertThat(count("creator_draft_video", task)).isEqualTo(1);
        var value = generated.register(lease, attempt.attemptId());
        assertThat(value.status()).isEqualTo("UPLOADED");
        assertThat(value.durationMs()).isNull();
        assertThat(value.versionNo()).isEqualTo(2);
        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionId()).isEqualTo(value.versionId());
        assertThat(mapper.findDraftVideoByVersion(task, "default", old.versionId()).orElseThrow().status()).isEqualTo("READY_FOR_REVIEW");
        var context = mapper.findVersionContext(task, "default", value.versionId()).orElseThrow();
        assertThat(context.planningSkipped()).isFalse();
        assertThat(context.snapshotJson()).contains("SYSTEM_RENDER", lease.runId(), "原始素材");
        assertThat(runtimeMapper.run(task, "default", lease.runId()).orElseThrow().stage()).isEqualTo("ARTIFACT_READY");
        assertThat(runtimeMapper.run(task, "default", lease.runId()).orElseThrow().status()).isEqualTo("RUNNING");
        assertThatThrownBy(() -> runtime.complete(task, "default", lease.runId(), lease.worker(), lease.generation(), "{\"done\":true}"))
                .hasMessageContaining("完整试映");
        assertThat(mapper.listGeneratedObjectKeys(task, "default", value.versionId())).hasSize(8);
        long revision = revision(task);
        assertThat(generated.register(lease, attempt.attemptId()).versionId()).isEqualTo(value.versionId());
        assertThat(revision(task)).isEqualTo(revision);
        assertThat(count("creator_draft_video", task)).isEqualTo(2);
    }

    @Test void creationMediaLinkKeepsOneVersionPerRunAndRefusesCompletionWithoutPackage() {
        String task = task(); readyVideo(task, "before-media");
        var lease = fullLease(task, fullInput(task, "c".repeat(64)));
        var attempt = prepareGenerated(lease, "attempt-media");
        var draft = generated.register(lease, attempt.attemptId());
        creationMedia.begin(lease, draft.versionId());
        var link = mediaLinks.find(lease.runId()).orElseThrow();
        assertThat(link.stage()).isEqualTo("MEDIA_PENDING");
        assertThat(link.versionId()).isEqualTo(draft.versionId());
        var waiting = runtimeMapper.run(task, "default", lease.runId()).orElseThrow();
        assertThat(waiting.status()).isEqualTo("WAITING_DEPENDENCY");
        assertThat(waiting.dependencyId()).isEqualTo(draft.versionId());
        // 旧租约已经随等待依赖释放，不能再用它冒充本次执行。
        assertThatThrownBy(() -> creationMedia.begin(lease, "not-this-run"))
                .hasMessageContaining("租约失效");
        // 没有通过对象校验的发布包不能结束整片运行。
        assertThatThrownBy(() -> runtime.completeProduction(task, "default", lease.runId(), lease.worker(),
                lease.generation(), "{\"done\":true}")).hasMessageContaining("最小发布包");
        // 重新领取后，其他版本仍然不能冒充本次运行的媒体闭环。
        runtime.wake(task, "default", lease.runId(), waiting.runRevision());
        var again = runtime.claim(task, "default", lease.runId(), "media-test", 30).orElseThrow();
        var renewed = new GeneratedDraftLease(task, "default", lease.runId(), "media-test", again.leaseGeneration());
        assertThatThrownBy(() -> creationMedia.begin(renewed, "not-this-run"))
                .hasMessageContaining("媒体处理版本不属于本次运行");
        creationMedia.begin(renewed, draft.versionId());
        assertThat(count("creator_creation_media_link", task)).isEqualTo(1);
        assertThat(mediaLinks.find(lease.runId()).orElseThrow().stage()).isEqualTo("MEDIA_PENDING");
    }

    @Test void dependencyChargesAreReservedSentAndSettledExactlyOnce() {
        String task = task(); readyVideo(task, "before-charge");
        // 依赖阶段的付费调用必须是本次真实授权的范围，这里给整片运行 5 元授权。
        runtime.initialize(task, "default", "budget-charge", revision(task), cny("20"));
        var queued = runtime.startProduction(task, "default", "full-charge", revision(task),
                fullInput(task, "c".repeat(64)), cny("5"));
        var claimed = runtime.claim(task, "default", queued.runId(), "media-test", 600).orElseThrow();
        var lease = new GeneratedDraftLease(task, "default", claimed.runId(), "media-test",
                claimed.leaseGeneration());
        var attempt = prepareGenerated(lease, "attempt-charge");
        var draft = generated.register(lease, attempt.attemptId());
        creationMedia.begin(lease, draft.versionId());
        var charge = runtime.reserveDependency(task, "default", lease.runId(), "asr-charge-1", "MEDIA_ASR",
                cny("0.01"), "{\"basis\":\"test\"}");
        assertThat(charge.status()).isEqualTo("RESERVED");
        assertThat(runtime.reserveDependency(task, "default", lease.runId(), "asr-charge-1", "MEDIA_ASR",
                cny("0.01"), "{\"basis\":\"test\"}").entryId()).isEqualTo(charge.entryId());
        assertThatThrownBy(() -> runtime.reserveDependency(task, "default", lease.runId(), "asr-charge-1",
                "MEDIA_ASR", cny("0.02"), "{\"basis\":\"test\"}")).hasMessageContaining("其他费用预留");
        assertThat(runtime.markSentDependency(task, "default", "asr-charge-1")).isTrue();
        assertThat(runtime.markSentDependency(task, "default", "asr-charge-1")).isFalse();
        runtime.settle(task, "default", "asr-charge-1", cny("0.007"), "USAGE_ESTIMATE", "usage:1");
        assertThat(runtime.snapshot(task, "default").budget().knownCny()).isEqualByComparingTo("0.007000");
        assertThat(runtime.snapshot(task, "default").budget().reservedCny()).isEqualByComparingTo("0");
    }

    @Test void packageBeginKeepsReadyFactsAndOnlyReopensUnfinishedBuilds() {
        String task = task();
        var first = new com.link.linkagent.creator.creation.model.CreationPackageRecord("pkg-run-" + task,
                task, "default", "pkg-version-" + task, "job", "review", "BUILDING", null, null, null, null,
                "{}", 1, null);
        assertThat(creationPackages.begin(first)).isEqualTo(1);
        // 未完成构建允许新代数接手（MySQL 对 ODKU 更新计为 2，不按行数判断业务结果）
        var takeover = new com.link.linkagent.creator.creation.model.CreationPackageRecord(first.runId(), task,
                "default", first.versionId(), "job", "review", "BUILDING", null, null, null, null, "{}", 2, null);
        creationPackages.begin(takeover);
        assertThat(creationPackages.find(first.runId()).orElseThrow().leaseGeneration()).isEqualTo(2);
        // READY 之后重复受理不再重建，也不改写已登记的对象事实
        var ready = new com.link.linkagent.creator.creation.model.CreationPackageRecord(first.runId(), task,
                "default", first.versionId(), "job", "review", "BUILDING", "bucket",
                "users/default/tasks/" + task + "/versions/" + first.versionId() + "/package/publish-package.zip",
                2048L, "f".repeat(64), "{\"k\":1}", 2, null);
        assertThat(creationPackages.ready(ready)).isEqualTo(1);
        var rebuild = new com.link.linkagent.creator.creation.model.CreationPackageRecord(first.runId(), task,
                "default", first.versionId(), "job", "review", "BUILDING", null, null, null, null, "{}", 3, null);
        creationPackages.begin(rebuild);
        var after = creationPackages.find(first.runId()).orElseThrow();
        assertThat(after.status()).isEqualTo("READY");
        assertThat(after.leaseGeneration()).isEqualTo(2);
        assertThat(after.objectKey()).isEqualTo(ready.objectKey());
        assertThat(after.bytes()).isEqualTo(2048L);
    }

    @Test void realMediaWorkerCompletesAllStagesAndRestoresReadyPackage() throws Exception {
        var fixture = mediaFixture(cny("0"));
        try (var worker = fixture.worker()) {
            fixture.until(worker, "PACKAGE_READY");
            assertThat(mediaLinks.candidates()).extracting(CreationMediaLink::runId).contains(fixture.lease.runId());
            assertThat(runtime.getRun(fixture.task, "default", fixture.lease.runId()).attemptCount()).isGreaterThan(3);
        }
        // 新 worker 必须从真实候选 SQL 找到已 READY 的包，不能由测试直接喂入候选。
        try (var restored = fixture.worker()) { fixture.until(restored, "COMPLETED"); }
        var done = runtimeMapper.run(fixture.task, "default", fixture.lease.runId()).orElseThrow();
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.attemptCount()).isEqualTo(6);
        assertThat(runtimeMapper.workspace(fixture.task, "default").orElseThrow().activeRunId()).isNull();
        assertThat(count("creator_media_processing_job", fixture.task)).isEqualTo(1);
        assertThat(count("creator_preflight_review", fixture.task)).isEqualTo(1);
        assertThat(fixture.packageUploads).hasValue(1);
        byte[] archive = fixture.storageBytes.get(creationPackages.find(fixture.lease.runId()).orElseThrow().objectKey());
        var entries = new java.util.HashMap<String, String>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                entries.put(entry.getName(), new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThat(entries).hasSize(10);
        assertThat(entries.get("publishing.md")).contains("数据库冻结的真实标题", "数据库冻结的简介");
        assertThat(entries.get("package-manifest.json")).contains("\"published\":false");
        System.out.println("S3_REPAIR_REAL_DB_WORKER_COMPLETED attempts=" + done.attemptCount() + " zipEntries=" + entries.size());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void deletingMediaRemovesItsRealPackageAndRetriesLegacyLeftovers(boolean previouslyDeleted) throws Exception {
        var fixture=mediaFixture(cny("0"));
        try(var worker=fixture.worker()){fixture.until(worker,"COMPLETED");}
        var ready=creationPackages.find(fixture.lease.runId()).orElseThrow();
        assertThat(ready.status()).isEqualTo("READY");
        var retainedReview=reviews.findReview(fixture.task,"default",ready.preflightReviewId()).orElseThrow();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(fixture.storageBytes.get(ready.objectKey())))) {
            var entries=new java.util.HashMap<String,String>();
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())
                entries.put(entry.getName(),new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            assertThat(entries.get("video.mp4")).isEqualTo("隔离调度协议夹具-video.mp4");
        }
        var other=readyVideo(fixture.task,"keep-other-version");
        String otherRun="other-"+fixture.task,otherKey="other-package/"+other.versionId()+".zip";
        jdbc.update("""
                INSERT INTO creator_creation_package(run_id,task_id,owner_id,version_id,processing_job_id,
                    preflight_review_id,status,bucket_name,object_key,bytes,manifest_json,lease_generation)
                VALUES (?,?,'default',?,'other-job','other-review','READY','bucket',?,1,'{}',1)
                """,otherRun,fixture.task,other.versionId(),otherKey);
        fixture.storageBytes.put(otherKey,new byte[]{1});
        if(previouslyDeleted)assertThat(mapper.markDraftMediaDeleted(fixture.task,"default",fixture.version)).isEqualTo(1);
        var firstDelete=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call->{
            String key=call.getArgument(1);
            if(ready.objectKey().equals(key) && firstDelete.getAndSet(false))throw new com.link.linkagent.creator.media.storage.MediaStorageException("隔离删除故障",null);
            fixture.storageBytes.remove(key);return null;
        }).when(fixture.storage).deleteObject(anyString(),anyString());
        var deletion=new MediaDeletionService(mapper,processing,reviews,fixture.storage);
        assertThatThrownBy(()->transactions.executeWithoutResult(status->deletion.deleteMedia("default",fixture.task,fixture.version)))
                .isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(503));
        assertThat(creationPackages.find(fixture.lease.runId()).orElseThrow().status()).isEqualTo("READY");
        assertThat(mapper.findDraftVideoByVersion(fixture.task,"default",fixture.version).orElseThrow().mediaDeletedAt()!=null).isEqualTo(previouslyDeleted);
        assertThat(fixture.storageBytes).containsKey(ready.objectKey());

        transactions.executeWithoutResult(status->deletion.deleteMedia("default",fixture.task,fixture.version));
        transactions.executeWithoutResult(status->deletion.deleteMedia("default",fixture.task,fixture.version));
        var deleted=creationPackages.find(fixture.lease.runId()).orElseThrow();
        assertThat(fixture.storageBytes).doesNotContainKey(ready.objectKey()).containsKey(otherKey);
        assertThat(deleted.status()).isEqualTo("FAILED");assertThat(deleted.failureCode()).isEqualTo("MEDIA_DELETED");
        assertThat(deleted.manifestJson()).isEqualTo(ready.manifestJson());
        assertThat(mapper.findDraftVideoByVersion(fixture.task,"default",fixture.version).orElseThrow().mediaDeletedAt()).isNotNull();
        assertThat(creationPackages.find(otherRun).orElseThrow().status()).isEqualTo("READY");
        assertThat(mapper.findDraftVideoByVersion(fixture.task,"default",other.versionId()).orElseThrow().mediaDeletedAt()).isNull();
        assertThat(reviews.findReview(fixture.task,"default",ready.preflightReviewId()).orElseThrow()).isEqualTo(retainedReview);
    }

    @Test void sameStageFailuresStayBoundedAcrossWorkerRestartAndCanBeCancelled() throws Exception {
        var fixture = mediaFixture(cny("0"));
        doThrow(new IllegalStateException("隔离测试的临时受理故障"))
                .when(fixture.processingService).createJob(any(), any(), any(), any(), any());
        try (var worker = fixture.worker()) { fixture.until(worker, "FAILED"); }
        verify(fixture.processingService, times(3)).createJob(any(), any(), any(), any(), any());
        var held = runtime.getRun(fixture.task, "default", fixture.lease.runId());
        assertThat(held.status()).isEqualTo("WAITING_DEPENDENCY");
        assertThat(held.errorCode()).isEqualTo("MEDIA_ATTEMPTS_EXHAUSTED");
        try (var restored = fixture.worker()) {
            for (int i = 0; i < 3; i++) restored.poll();
            verify(fixture.processingService, times(3)).createJob(any(), any(), any(), any(), any());
            runtime.cancel(fixture.task, "default", fixture.lease.runId());
            fixture.until(restored, "CANCELLED");
        }
        assertThat(runtime.getRun(fixture.task, "default", fixture.lease.runId()).status()).isEqualTo("CANCELLED");
    }

    @Test void successfulStageResetsRetryAllowanceForTheNextStage() throws Exception {
        var fixture = mediaFixture(cny("0"));
        var processingCalls = new java.util.concurrent.atomic.AtomicInteger();
        var reviewCalls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (processingCalls.incrementAndGet() <= 2) throw new IllegalStateException("预处理受理暂不可用");
            return fixture.acceptProcessing(call.getArgument(4));
        }).when(fixture.processingService).createJob(any(), any(), any(), any(), any());
        doAnswer(call -> {
            if (reviewCalls.incrementAndGet() <= 2) throw new IllegalStateException("试映受理暂不可用");
            return fixture.acceptReview(call.getArgument(4));
        }).when(fixture.preflightService).create(any(), any(), any(), any(), any());
        try (var worker = fixture.worker()) { fixture.until(worker, "COMPLETED"); }
        assertThat(processingCalls).hasValue(3); assertThat(reviewCalls).hasValue(3);
        assertThat(runtime.getRun(fixture.task, "default", fixture.lease.runId()).attemptCount()).isEqualTo(10);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"QUEUED", "EXPIRED", "LIVE"})
    void mediaWorkerOwnsRecoveryAndFixedWorkerCannotTakeTheRunBack(String state) throws Exception {
        var fixture = mediaFixture(cny("0"));
        var job = new FixedProjectJob(fixture.lease.runId(), fixture.task, "default", "fixture", "{}");
        fixedJobMapper.insert(job); fixedJobsToClean.add(job);
        var waiting = runtime.getRun(fixture.task, "default", fixture.lease.runId());
        runtime.wake(fixture.task, "default", fixture.lease.runId(), waiting.runRevision());
        if (!"QUEUED".equals(state)) {
            runtime.claim(fixture.task, "default", fixture.lease.runId(), "lost-media-worker", 600).orElseThrow();
            if ("EXPIRED".equals(state)) jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE run_id=?", fixture.lease.runId());
        }
        assertThat(fixedJobMapper.candidates()).extracting(FixedProjectJob::runId).doesNotContain(fixture.lease.runId());
        assertThat(runtime.claimFixed(fixture.task, "default", fixture.lease.runId(), "stale-fixed-candidate", 30)).isEmpty();
        try (var worker = fixture.worker()) {
            if ("LIVE".equals(state)) {
                assertThat(mediaLinks.candidates()).extracting(CreationMediaLink::runId).doesNotContain(fixture.lease.runId());
                worker.poll(); verify(fixture.processingService, never()).createJob(any(), any(), any(), any(), any());
                runtime.cancel(fixture.task, "default", fixture.lease.runId());
                jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE run_id=?", fixture.lease.runId());
                fixture.until(worker, "CANCELLED");
            } else fixture.until(worker, "COMPLETED");
        }
    }

    @Test void staleMediaStageAdvanceCannotPolluteThePersistedRetryBaseline() throws Exception {
        var fixture = mediaFixture(cny("0"));
        var expected = mediaLinks.find(fixture.lease.runId()).orElseThrow();
        runtime.wake(fixture.task, "default", fixture.lease.runId(), runtime.getRun(fixture.task, "default", fixture.lease.runId()).runRevision());
        var old = runtime.claim(fixture.task, "default", fixture.lease.runId(), "old-media", 30).orElseThrow();
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE run_id=?", fixture.lease.runId());
        var current = runtime.claim(fixture.task, "default", fixture.lease.runId(), "new-media", 30).orElseThrow();
        assertThat(mediaLinks.advance(fixture.lease.runId(), "MEDIA_PENDING", "MEDIA_RUNNING", "old-job", null, null, old.leaseGeneration())).isZero();
        var stale = new GeneratedDraftLease(fixture.task, "default", fixture.lease.runId(), "old-media", old.leaseGeneration());
        assertThatThrownBy(() -> creationMedia.advanceAndWait(stale, expected, "MEDIA_RUNNING", "old-job", null, "old-job"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(mediaLinks.find(fixture.lease.runId()).orElseThrow()).isEqualTo(expected);
        var lease = new GeneratedDraftLease(fixture.task, "default", fixture.lease.runId(), "new-media", current.leaseGeneration());
        creationMedia.hold(lease, expected, "ISOLATED_HOLD");
        runtime.cancel(fixture.task, "default", fixture.lease.runId());
        try (var worker = fixture.worker()) { fixture.until(worker, "CANCELLED"); }
    }

    @Test void dependencyBillingReusesPersistedRateAfterServiceRestartAndRejectsCrossRun() throws Exception {
        var fixture = mediaFixture(cny("5"));
        jdbc.update("UPDATE creator_creation_media_link SET preflight_review_id=? WHERE run_id=?", "bill-" + fixture.task, fixture.lease.runId());
        var props = new com.link.linkagent.creator.creation.service.CreationBillingProperties();
        var billing = new com.link.linkagent.creator.creation.service.CreationMediaBilling(mediaLinks, runtime, props, new ObjectMapper());
        String reviewId = "bill-" + fixture.task;
        assertThat(billing.reserve(reviewId, "MEDIA_ASR", "frozen-rate", cny("0.01"), "real-db-price").markSent()).isTrue();
        props.setUsdToCny(cny("8.00"));
        var restored = new com.link.linkagent.creator.creation.service.CreationMediaBilling(mediaLinks, runtime, props, new ObjectMapper());
        assertThat(restored.reserve(reviewId, "MEDIA_ASR", "frozen-rate", cny("0.01"), "real-db-price").markSent()).isFalse();
        restored.settle(reviewId, "frozen-rate", cny("0.02"), "same-call");
        props.setUsdToCny(cny("9.00")); restored.settle(reviewId, "frozen-rate", cny("0.02"), "same-call");
        var charge = runtime.findCharge(fixture.task, "default", fixture.lease.runId(), "frozen-rate").orElseThrow();
        assertThat(charge.knownCostCny()).isEqualByComparingTo("0.144000");
        assertThat(charge.priceSnapshotJson()).contains("7.20");
        assertThat(runtime.snapshot(fixture.task, "default").budget().knownCny()).isEqualByComparingTo("0.144000");
        assertThatThrownBy(() -> runtime.findCharge(fixture.task, "default", "other-run", "frozen-rate"))
                .isInstanceOf(ResponseStatusException.class);
        runtime.cancel(fixture.task, "default", fixture.lease.runId());
        try (var worker = fixture.worker()) { fixture.until(worker, "CANCELLED"); }
    }

    @Test void childAcceptanceAndStageRollbackTogetherWhenLeaseExpiresInsideTheTransaction() throws Exception {
        var fixture = mediaFixture(cny("0"));
        var expected = mediaLinks.find(fixture.lease.runId()).orElseThrow();
        runtime.wake(fixture.task, "default", fixture.lease.runId(), runtime.getRun(fixture.task, "default", fixture.lease.runId()).runRevision());
        var claimed = runtime.claim(fixture.task, "default", fixture.lease.runId(), "expiry-media", 30).orElseThrow();
        var lease = new GeneratedDraftLease(fixture.task, "default", fixture.lease.runId(), "expiry-media", claimed.leaseGeneration());
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,1,CURRENT_TIMESTAMP(6)) WHERE run_id=?", fixture.lease.runId());
        assertThatThrownBy(() -> creationMedia.acceptProcessing(lease, expected, () -> {
            var reference = mock(MediaUploadRecord.class);
            when(reference.taskId()).thenReturn(fixture.task); when(reference.versionId()).thenReturn(fixture.version);
            String child = processingJob(reference, "QUEUED");
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return child;
        })).isInstanceOf(ResponseStatusException.class);
        assertThat(count("creator_media_processing_job", fixture.task)).isZero();
        assertThat(mediaLinks.find(fixture.lease.runId()).orElseThrow()).isEqualTo(expected);
        runtime.cancel(fixture.task, "default", fixture.lease.runId());
        try (var worker = fixture.worker()) { fixture.until(worker, "CANCELLED"); }
    }

    /** 仍执行真实候选 SQL，只把测试扫描范围限定在自己的运行，避免不同夹具相互推进。 */
    private static com.link.linkagent.creator.creation.mapper.CreationMediaMapper scopedMediaCandidates(String runId) {
        var scoped = mock(com.link.linkagent.creator.creation.mapper.CreationMediaMapper.class);
        when(scoped.candidates()).thenAnswer(call -> mediaLinks.candidates().stream().filter(link -> runId.equals(link.runId())).toList());
        return scoped;
    }

    /** 真实数据库驱动整个调度；子任务完成与对象存储是受控边界，不冒充供应商试映或可播放视频。 */
    private static MediaLoopFixture mediaFixture(BigDecimal authorization) throws Exception {
        String task = task(); String input = fullInput(task, "c".repeat(64));
        jdbc.update("UPDATE creator_suggestion SET title_suggestions=?, description_suggestion=? WHERE task_id=?", "[\"数据库冻结的真实标题\"]", "数据库冻结的简介", task);
        runtime.initialize(task, "default", "loop-budget", revision(task), cny("20"));
        var queued = runtime.startProduction(task, "default", "loop", revision(task), input, authorization);
        var run = runtime.claim(task, "default", queued.runId(), "loop-render", 600).orElseThrow();
        var lease = new GeneratedDraftLease(task, "default", run.runId(), "loop-render", run.leaseGeneration());
        var payloads = new java.util.HashMap<String, byte[]>();
        var files = new ArrayList<GeneratedDraftFile>();
        for (var descriptor : generatedFiles()) {
            byte[] content = ("隔离调度协议夹具-" + descriptor.name()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            payloads.put(descriptor.name(), content);
            files.add(new GeneratedDraftFile(descriptor.name(), descriptor.contentType(), content.length, nativeSha(content)));
        }
        var attempt = generated.prepare(lease, "loop-attempt", "a".repeat(64), "c".repeat(64), "board", "bucket", files);
        var draft = generated.register(lease, attempt.attemptId());
        creationMedia.begin(lease, draft.versionId());
        return new MediaLoopFixture(task, lease, draft.versionId(), payloads);
    }

    private static final class MediaLoopFixture {
        final String task, version; final GeneratedDraftLease lease;
        final MediaProcessingService processingService = mock(MediaProcessingService.class);
        final PreflightReviewService preflightService = mock(PreflightReviewService.class);
        final ObjectStorageService storage = mock(ObjectStorageService.class);
        final Map<String, byte[]> storageBytes = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicInteger packageUploads = new java.util.concurrent.atomic.AtomicInteger();
        final com.link.linkagent.creator.creation.service.CreationPackageService packageService;
        String jobId, reviewId;

        MediaLoopFixture(String task, GeneratedDraftLease lease, String version, Map<String, byte[]> source) throws Exception {
            this.task=task; this.lease=lease; this.version=version;
            doAnswer(call -> { java.nio.file.Files.write(call.getArgument(2), source.get(((String)call.getArgument(1)).replaceAll(".*/", ""))); return null; })
                    .when(storage).downloadObject(anyString(), anyString(), any());
            doAnswer(call -> { storageBytes.put(call.getArgument(1), java.nio.file.Files.readAllBytes(call.getArgument(2))); packageUploads.incrementAndGet(); return null; })
                    .when(storage).putObject(anyString(), anyString(), any(), anyString());
            when(storage.headObject(anyString(), anyString())).thenAnswer(call -> new StoredObjectMetadata(storageBytes.get(call.getArgument(1)).length, "application/zip", "isolated-etag"));
            doAnswer(call -> acceptProcessing(call.getArgument(4))).when(processingService).createJob(any(), any(), any(), any(), any());
            when(processingService.getCurrentJob(any(), any(), any())).thenAnswer(call -> jobResponse());
            doAnswer(call -> acceptReview(call.getArgument(4))).when(preflightService).create(any(), any(), any(), any(), any());
            when(preflightService.get(any(), any(), any())).thenAnswer(call -> reviewResponse());
            packageService = new com.link.linkagent.creator.creation.service.CreationPackageService(creationPackages, generatedMapper, mapper, storage, preflightService, runtime, new ObjectMapper());
        }

        private com.link.linkagent.creator.media.upload.model.MediaUploadRecord reference() {
            var reference = mock(com.link.linkagent.creator.media.upload.model.MediaUploadRecord.class);
            when(reference.taskId()).thenReturn(task); when(reference.versionId()).thenReturn(version); return reference;
        }
        com.link.linkagent.creator.media.processing.model.MediaProcessingJobResponse acceptProcessing(com.link.linkagent.creator.media.access.InternalMediaAcceptance acceptance) {
            acceptance.verify(task, "default", version);
            jobId = processingJob(reference(), "COMPLETED"); return jobResponse();
        }
        com.link.linkagent.creator.media.preflight.model.PreflightReviewResponse acceptReview(com.link.linkagent.creator.media.access.InternalMediaAcceptance acceptance) {
            acceptance.verify(task, "default", version);
            reviewId = review(reference(), jobId, "COMPLETED"); return reviewResponse();
        }
        private com.link.linkagent.creator.media.processing.model.MediaProcessingJobResponse jobResponse() {
            var result = mock(com.link.linkagent.creator.media.processing.model.MediaProcessingJobResponse.class);
            when(result.jobId()).thenReturn(jobId); when(result.status()).thenReturn("COMPLETED"); return result;
        }
        private com.link.linkagent.creator.media.preflight.model.PreflightReviewResponse reviewResponse() {
            var result = mock(com.link.linkagent.creator.media.preflight.model.PreflightReviewResponse.class);
            when(result.reviewId()).thenReturn(reviewId); when(result.status()).thenReturn("COMPLETED");
            when(result.taskId()).thenReturn(task); when(result.versionId()).thenReturn(version); return result;
        }
        com.link.linkagent.creator.creation.service.CreationMediaWorker worker() {
            var properties = new com.link.linkagent.creator.creation.service.CreationMediaProperties(); properties.setEnabled(true);
            return new com.link.linkagent.creator.creation.service.CreationMediaWorker(properties, scopedMediaCandidates(lease.runId()), creationPackages, generatedMapper, runtime, processingService, preflightService, packageService, creationMedia);
        }
        void until(com.link.linkagent.creator.creation.service.CreationMediaWorker worker, String stage) throws Exception {
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime()<deadline) {
                var actual=mediaLinks.find(lease.runId()).orElseThrow();
                if (stage.equals(actual.stage())) return;
                worker.poll(); Thread.sleep(25);
            }
            assertThat(mediaLinks.find(lease.runId()).orElseThrow().stage()).as("媒体阶段").isEqualTo(stage);
        }
    }

    @Test void fullRunRequiresRealConfirmationEvenIfPlanningWasSkipped() {
        String task = task(); runtime.initialize(task, "default", "budget", -1, cny("20"));
        String input = "{\"publishingSessionId\":\"missing\",\"publishingResultId\":\"missing\",\"productionPlanId\":\"missing\",\"baseVersionId\":null,\"storyboardId\":\"board\",\"renderContentHash\":\"" + "c".repeat(64) + "\"}";
        long before = revision(task);
        assertThatThrownBy(() -> runtime.startProduction(task, "default", "full", before, input, cny("0"))).hasMessageContaining("真实发布确认");
        assertThat(count("creator_creation_approval", task)).isZero();
        assertThat(count("creator_creation_run", task)).isZero();
        assertThat(revision(task)).isEqualTo(before);
    }

    @Test void fullRunAuthorizationIsAtomicIdempotentAndCannotBeUsedForPreparation() {
        String task = task(); String input = fullInput(task, "c".repeat(64));
        runtime.initialize(task, "default", "budget", revision(task), cny("20")); long before = revision(task);
        var run = runtime.startProduction(task, "default", "full", before, input, cny("0"));
        assertThat(runtime.startProduction(task, "default", "full", before, input, cny("0")).runId()).isEqualTo(run.runId());
        assertThatThrownBy(() -> runtime.startProduction(task, "default", "full", before, input, cny("1"))).hasMessageContaining("内容不同");
        runtime.cancel(task, "default", run.runId());
        assertThatThrownBy(() -> runtime.start(task, "default", "TEXT", "wrong-kind", revision(task), run.approvalId(), input)).hasMessageContaining("不一致");
        assertThatThrownBy(() -> runtime.start(task, "default", "FULL", "reuse-plan", revision(task), run.approvalId(), input)).hasMessageContaining("专属计划");
    }

    @Test void generatedDraftCannotSkipUnfinishedOrEmptyBlueprintSteps() {
        String task = task(); var lease = fullLease(task, fullInput(task, "c".repeat(64)));
        prepareGenerated(lease, "attempt-one");
        jdbc.update("UPDATE creator_production_step SET status='PENDING' WHERE task_id=?", task);
        assertThatThrownBy(() -> generated.register(lease, "attempt-one")).hasMessageContaining("尚未完成");
        assertThat(count("creator_draft_video", task)).isZero();
        jdbc.update("DELETE FROM creator_production_step WHERE task_id=?", task);
        assertThatThrownBy(() -> generated.register(lease, "attempt-one")).hasMessageContaining("尚未完成");
        assertThat(mapper.findMediaWorkspace(task, "default").orElseThrow().nextVersionNo()).isEqualTo(1);
    }

    @Test void generatedDraftCannotUseChangedContentOrStoryboard() {
        String task = task(); var lease = fullLease(task, fullInput(task, "c".repeat(64)));
        assertThatThrownBy(() -> generated.prepare(lease, "attempt", "a".repeat(64), "b".repeat(64), "board", "bucket", generatedFiles()))
                .hasMessageContaining("授权不一致");
        assertThatThrownBy(() -> generated.prepare(lease, "attempt", "a".repeat(64), "c".repeat(64), "other", "bucket", generatedFiles()))
                .hasMessageContaining("授权不一致");
        assertThat(count("creator_creation_render_attempt", task)).isZero();
    }

    @Test void generatedAttemptReplayRejectsChangedManifestOrGeneration() {
        String task = task(); var lease = fullLease(task, fullInput(task, "c".repeat(64)));
        var first = prepareGenerated(lease, "attempt");
        assertThat(prepareGenerated(lease, "attempt").versionId()).isEqualTo(first.versionId());
        assertThatThrownBy(() -> generated.prepare(lease, "attempt", "b".repeat(64), "c".repeat(64), "board", "bucket", generatedFiles())).hasMessageContaining("同一尝试");
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE run_id=?", lease.runId());
        var claimed = runtime.claim(task, "default", lease.runId(), "replacement", 600).orElseThrow();
        var replacement = new GeneratedDraftLease(task, "default", lease.runId(), "replacement", claimed.leaseGeneration());
        assertThatThrownBy(() -> generated.register(lease, "attempt")).hasMessageContaining("失效");
        assertThatThrownBy(() -> prepareGenerated(replacement, "attempt")).hasMessageContaining("代数");
        var next = prepareGenerated(replacement, "attempt-two");
        assertThat(generated.register(replacement, next.attemptId()).versionId()).isEqualTo(next.versionId());
        assertThatThrownBy(() -> generated.register(replacement, first.attemptId())).hasMessageContaining("代数");
        assertThat(count("creator_draft_video", task)).isEqualTo(1);
    }

    @Test void cancelledOrRevokedProductionCannotRegisterUploadedObjects() {
        String task = task(); var lease = fullLease(task, fullInput(task, "c".repeat(64)));
        prepareGenerated(lease, "attempt"); runtime.cancel(task, "default", lease.runId());
        assertThatThrownBy(() -> generated.register(lease, "attempt")).hasMessageContaining("取消");
        assertThat(mapper.findDraftVideo(task, "default")).isEmpty();
        String second = task(); var secondLease = fullLease(second, fullInput(second, "c".repeat(64)));
        prepareGenerated(secondLease, "attempt");
        var run = runtimeMapper.run(second, "default", secondLease.runId()).orElseThrow();
        runtime.revokeApproval(second, "default", run.approvalId());
        assertThatThrownBy(() -> generated.register(secondLease, "attempt")).hasMessageContaining("失效");
        assertThat(count("creator_draft_video", second)).isZero();
    }

    @Test void changedCurrentVersionDuringUploadCannotBeOverwritten() {
        String task = task(); var old = readyVideo(task, "before-generated");
        var lease = fullLease(task, fullInput(task, "c".repeat(64))); prepareGenerated(lease, "attempt");
        jdbc.update("UPDATE creator_creation_workspace SET current_video_version_id=NULL WHERE task_id=?", task);
        assertThatThrownBy(() -> generated.register(lease, "attempt")).hasMessageContaining("基准成片");
        assertThat(count("creator_draft_video", task)).isEqualTo(1);
        assertThat(mapper.findDraftVideoByVersion(task, "default", old.versionId())).isPresent();
    }

    @Test void systemRenderedContextDoesNotInheritTaskPlanningSkip() {
        String task = task(); var lease = fullLease(task, fullInput(task, "c".repeat(64)));
        jdbc.update("UPDATE creator_task SET planning_skipped=1 WHERE task_id=?", task);
        var attempt = prepareGenerated(lease, "attempt"); var draft = generated.register(lease, attempt.attemptId());
        assertThat(mapper.findVersionContext(task, "default", draft.versionId()).orElseThrow().planningSkipped()).isFalse();
    }

    @Test void generatedMediaDeletionIncludesEveryRegisteredArtifact() {
        String task = task(); var lease = fullLease(task, fullInput(task, "c".repeat(64)));
        prepareGenerated(lease, "attempt"); var draft = generated.register(lease, "attempt");
        runtime.cancel(task, "default", lease.runId()); runtime.acknowledgeCancellation(task, "default", lease.runId(), lease.worker(), lease.generation());
        var storage = mockStorage(); new MediaDeletionService(mapper, processing, reviews, storage).deleteMedia("default", task, draft.versionId());
        verify(storage, times(8)).deleteObject(eq("bucket"), anyString());
        assertThat(mapper.findDraftVideoByVersion(task, "default", draft.versionId()).orElseThrow().mediaDeletedAt()).isNotNull();
        assertThat(mapper.findVersionContext(task, "default", draft.versionId())).isPresent();
    }

    @Test void failedContextInsertRollsBackVersionNumberAndPointer() {
        String task = task(); var lease = fullLease(task, fullInput(task, "c".repeat(64)));
        var attempt = prepareGenerated(lease, "attempt");
        jdbc.update("INSERT INTO creator_video_version_context (version_id,task_id,owner_id,snapshot_json) VALUES (?,?,'default','{}')", attempt.versionId(), task);
        assertThatThrownBy(() -> generated.register(lease, "attempt")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("creator_draft_video", task)).isZero();
        assertThat(mapper.findMediaWorkspace(task, "default").orElseThrow().nextVersionNo()).isEqualTo(1);
        assertThat(mapper.findDraftVideo(task, "default")).isEmpty();
        assertThat(generatedMapper.attempt(task, "default", lease.runId(), "attempt").orElseThrow().status()).isEqualTo("UPLOADING");
    }

    @Test void registeredOutputRestoresAcrossLeaseTakeoverWithoutReselecting() {
        String task = task(); var lease = fullLease(task, fullInput(task, "c".repeat(64)));
        var attempt = prepareGenerated(lease, "attempt"); var draft = generated.register(lease, "attempt");
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE run_id=?", lease.runId());
        var next = runtime.claim(task, "default", lease.runId(), "replacement", 600).orElseThrow();
        var replacement = new GeneratedDraftLease(task, "default", lease.runId(), "replacement", next.leaseGeneration());
        long before = revision(task);
        assertThat(generated.restoreRegistered(replacement, attempt.attemptId(), attempt.inputHash()).orElseThrow().versionId()).isEqualTo(draft.versionId());
        assertThat(revision(task)).isEqualTo(before);
        assertThatThrownBy(() -> generated.restoreRegistered(replacement, "other", attempt.inputHash())).hasMessageContaining("其他尝试");
    }

    @Test void concurrentGeneratedRegistrationCreatesExactlyOneVersion() throws Exception {
        String task = task(); var lease = fullLease(task, fullInput(task, "c".repeat(64)));
        prepareGenerated(lease, "attempt"); var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { go.await(); return generated.register(lease, "attempt").versionId(); });
            var second = pool.submit(() -> { go.await(); return generated.register(lease, "attempt").versionId(); });
            go.countDown(); assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(count("creator_draft_video", task)).isEqualTo(1);
    }

    @Test
    void automaticPlanAdmissionIsIdempotentAndPreservesExistingVideoAndReview() throws Exception {
        String task = task(); var old = readyVideo(task, "before-auto"); confirmedPlan(task);
        runtime.initialize(task, "default", "auto-budget", revision(task), cny("20")); long before = revision(task);
        var request = automaticRequest(task, "c".repeat(64), 2000);
        var run = automaticPlans.start(task, "default", "auto-idempotent", before, request, cny("0"));
        fixedJobsToClean.add(new FixedProjectJob(run.runId(), task, "default", "source", "{}"));
        assertThat(automaticPlans.start(task, "default", "auto-idempotent", before, request, cny("0")).runId()).isEqualTo(run.runId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_production_plan WHERE task_id=?", Integer.class, task)).isEqualTo(2);
        var rows = automaticMapper.steps(task, "default", run.runId());
        assertThat(rows).hasSize(6); assertThat(rows).filteredOn(step -> "COMPLETED".equals(step.stepStatus())).hasSize(1);
        assertThat(rows.getFirst().stepCode()).isEqualTo("APPROVED");
        assertThat(mapper.findDraftVideo(task, "default").orElseThrow().versionId()).isEqualTo(old.versionId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_preflight_review WHERE version_id=? AND status='COMPLETED'", Integer.class, old.versionId())).isEqualTo(1);
        var changed = new AutomaticProductionRequest(request.publishingSessionId(), request.publishingResultId(), request.baseVersionId(), "board", request.renderContentHash(), 3000);
        assertThatThrownBy(() -> automaticPlans.start(task, "default", "auto-idempotent", before, changed, cny("0"))).hasMessageContaining("内容不同");
    }

    @Test
    void automaticPlanDoesNotInventMissingPublishingConfirmation() {
        String task = task();
        assertThatThrownBy(() -> automaticPlans.start(task, "default", "missing-confirmation", -1,
                new AutomaticProductionRequest("absent-session", "absent-result", null, "board", "c".repeat(64), 2000), cny("0")))
                .hasMessageContaining("真实发布方案确认");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_production_plan WHERE task_id=?", Integer.class, task)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_creation_run WHERE task_id=?", Integer.class, task)).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"SKIPPED", "NEW_SUGGESTION", "NEW_SESSION"})
    void automaticPlanRejectsSkippedOrSupersededPublishingContext(String change) {
        String task = task(); confirmedPlan(task); runtime.initialize(task, "default", "auto-budget", revision(task), cny("20"));
        var request = automaticRequest(task, "c".repeat(64), 2000);
        if ("SKIPPED".equals(change)) jdbc.update("UPDATE creator_task SET planning_skipped=1 WHERE task_id=?", task);
        else {
            String session = request.publishingSessionId(), suggestion = UUID.randomUUID().toString();
            if ("NEW_SESSION".equals(change)) {
                session = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO creator_workflow_session (session_id,task_id,stage,status,confirmed_result_id) VALUES (?,?,'PRE_PUBLISH','CONFIRMED',?)", session, task, suggestion);
            }
            // 同会话重新生成沿用业务 upsert，替换方案标识后旧确认必须失效。
            jdbc.update("INSERT INTO creator_suggestion (suggestion_id,task_id,session_id,content_summary,raw_output) VALUES (?,?,?,'新方案','{}') ON DUPLICATE KEY UPDATE suggestion_id=VALUES(suggestion_id),content_summary=VALUES(content_summary)", suggestion, task, session);
        }
        assertThatThrownBy(() -> automaticPlans.start(task, "default", "stale-auto", revision(task), request, cny("0"))).hasMessageContaining("真实发布方案确认");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_creation_auto_step WHERE task_id=?", Integer.class, task)).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void automaticPlanAndStepsRollBackWhenRunAdmissionFails(boolean overBudget) {
        String task = task(); confirmedPlan(task); runtime.initialize(task, "default", "auto-budget", revision(task), cny("20"));
        var request = automaticRequest(task, "c".repeat(64), 2000);
        assertThatThrownBy(() -> automaticPlans.start(task, "default", "atomic-auto", revision(task) + (overBudget ? 0 : 1), request,
                cny(overBudget ? "21" : "0"))).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_production_plan WHERE task_id=?", Integer.class, task)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_production_step WHERE task_id=?", Integer.class, task)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_creation_run WHERE task_id=?", Integer.class, task)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_creation_approval WHERE task_id=?", Integer.class, task)).isZero();
        assertThat(runtime.snapshot(task, "default").activeRun()).isNull();
    }

    @Test
    void automaticStepsCannotBeManuallyCompletedOrSkipped() throws Exception {
        var job = queuedAutomaticJob(task(), fixedDocument());
        var row = automaticMapper.steps(job.taskId(), "default", job.runId()).getLast();
        for (String state : List.of("COMPLETED", "SKIPPED")) {
            assertThatThrownBy(() -> production.updateStep(job.taskId(), row.productionPlanId(), row.stepId(), state, "手工操作夹具", 0))
                    .hasMessageContaining("不能手工勾选或跳过");
        }
        assertThat(automaticMapper.steps(job.taskId(), "default", job.runId()).getLast().stepStatus()).isEqualTo("PENDING");
        String manualTask = task(), manualPlan = confirmedPlan(manualTask), manualStep = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO creator_production_step (step_id,plan_id,task_id,sequence_no,phase,step_name,objective) VALUES (?,?,?,1,'SCRIPT','人工步骤','人工完成')", manualStep, manualPlan, manualTask);
        production.updateStep(manualTask, manualPlan, manualStep, "COMPLETED", null, 0);
        assertThat(jdbc.queryForObject("SELECT status FROM creator_production_step WHERE step_id=?", String.class, manualStep)).isEqualTo("COMPLETED");
    }

    @Test
    void taskDeletionKeepsActiveRunAndMaterialsUntilCancellationFinishes() {
        String task = task(); var run = preparedRun(task, "0");
        jdbc.update("INSERT INTO creator_material(task_id,material_type,content) VALUES (?,'SCRIPT','删除保护')", task);
        assertThatThrownBy(() -> taskService.deleteTask(task)).hasMessageContaining("制作运行或待结算费用");
        assertThat(mapper.countTaskByOwner(task,"default")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_material WHERE task_id=? AND is_deleted=0",Integer.class,task)).isEqualTo(1);
        runtime.cancel(task,"default",run.runId()); taskService.deleteTask(task);
        assertThat(mapper.countTaskByOwner(task,"default")).isZero();
        assertThat(runtimeMapper.workspace(task,"default").orElseThrow().activeRunId()).isNull();
    }

    @Test
    void unknownSentCostMustBeSettledBeforeTaskDeletion() {
        String task=task(); var run=claimed(preparedRun(task,"5"),"worker"); reserve(run,"late","2");
        String key=run.runId()+"late"; runtime.markSent(task,"default",key,"worker",run.leaseGeneration());
        runtime.cancel(task,"default",run.runId());
        assertThatThrownBy(() -> taskService.deleteTask(task)).hasMessageContaining("制作运行或待结算费用");
        runtime.acknowledgeCancellation(task,"default",run.runId(),"worker",run.leaseGeneration());
        assertThat(runtimeMapper.charge(task,"default",key).orElseThrow().status()).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> taskService.deleteTask(task)).hasMessageContaining("制作运行或待结算费用");
        runtime.settle(task,"default",key,cny("1"),"PROVIDER_REPORTED","review.receipt"); taskService.deleteTask(task);
        assertThat(runtimeMapper.charge(task,"default",key).orElseThrow().status()).isEqualTo("SETTLED");
        assertThat(mapper.countTaskByOwner(task,"default")).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={1,2,3})
    void taskDeletionAndRunAdmissionSerializeOnTheSameTask(int iteration) throws Exception {
        String task=task(); runtime.initialize(task,"default","budget",revision(task),cny("20"));
        var approval=runtime.approvePreparation(task,"default","approve",revision(task),"{\"script\":\"测试\"}",cny("0"));
        long expected=revision(task); var go=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var start=pool.submit(() -> { go.await(); try { runtime.start(task,"default","TEXT","start",expected,approval.approvalId(),"{\"script\":\"测试\"}"); return true; } catch (ResponseStatusException e) { return false; } });
            var delete=pool.submit(() -> { go.await(); try { taskService.deleteTask(task); return true; } catch (ResponseStatusException e) { return false; } });
            go.countDown();
            assertThat(List.of(start.get(15,TimeUnit.SECONDS),delete.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        if (mapper.countTaskByOwner(task,"default")==1) {
            var active=runtime.snapshot(task,"default").activeRun(); assertThat(active).isNotNull(); runtime.cancel(task,"default",active.runId());
        } else assertThat(runtimeMapper.workspace(task,"default").orElseThrow().activeRunId()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void terminatedIncompleteAutomaticPlanReturnsToOwnVideoUpload(boolean fail) {
        String task=task(); var old=readyVideo(task,"manual-video"); String manual=confirmedPlan(task);
        jdbc.update("INSERT INTO creator_production_step(step_id,plan_id,task_id,sequence_no,phase,step_name,objective,status) VALUES (?,?,?,1,'SCRIPT','人工完成','回退依据','COMPLETED')",UUID.randomUUID().toString(),manual,task);
        var gate=new com.link.linkagent.creator.production.service.ProductionPlanGateService(productionMapper,mock(CreatorWorkflowMapper.class),mock(CreatorSuggestionMapper.class));
        assertThat(gate.requireReady(task,"default").planId()).isEqualTo(manual);
        runtime.initialize(task,"default","fallback-budget",revision(task),cny("20"));
        var request=automaticRequest(task,"c".repeat(64),2000); long expected=revision(task);
        var run=automaticPlans.start(task,"default","fallback-auto",expected,request,cny("0"));
        var rows=automaticMapper.steps(task,"default",run.runId()); String system=rows.getFirst().productionPlanId();
        if (fail) { var lease=runtime.claim(task,"default",run.runId(),"worker",60).orElseThrow(); runtime.fail(task,"default",run.runId(),"worker",lease.leaseGeneration(),"RENDER_FAILED"); }
        else runtime.cancel(task,"default",run.runId());
        assertThat(productionMapper.findPlan(task,"default",system).orElseThrow().status()).isEqualTo("STALE");
        assertThat(automaticMapper.steps(task,"default",run.runId())).filteredOn(s -> "PENDING".equals(s.stepStatus())).hasSize(5);
        assertThat(gate.requireReady(task,"default").planId()).isEqualTo(manual);
        assertThat(automaticPlans.start(task,"default","fallback-auto",expected,request,cny("0")).runId()).isEqualTo(run.runId());
        assertThat(mapper.findDraftVideo(task,"default").orElseThrow().versionId()).isEqualTo(old.versionId());
        var props=new CreatorMediaProperties(); props.setEnabled(true); var storageProps=new ObjectStorageProperties();
        storageProps.setBucket("isolated-test"); storageProps.setAccessKey("test-key"); storageProps.setSecretKey("test-secret");
        var storage=mock(ObjectStorageService.class);
        when(storage.createMultipartUpload(anyString(),anyString())).thenReturn(new com.link.linkagent.creator.media.storage.MultipartUploadHandle("own-cover-upload"));
        var uploads=new MediaUploadService(props,storageProps,storage,mapper,persistence,mock(CreatorMediaWorkflowGateService.class),gate);
        var upload=uploads.createUpload("default",task,"own-cover",new com.link.linkagent.creator.media.upload.model.CreateMediaUploadRequest("自己的覆盖成片","own.mp4",1024L,"video/mp4",2L));
        assertThat(upload.versionId()).isNotEqualTo(old.versionId());
        assertThat(mapper.findDraftVideo(task,"default").orElseThrow().versionId()).isEqualTo(old.versionId());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void completedAutomaticFactsRemainWhenRunIsCancelled(boolean register) throws Exception {
        var job=queuedAutomaticJob(task(),fixedDocument()); fixedJobs.attach(job); var lease=automaticLease(job);
        prepareAutomatic(lease,job.projectJson()); generated.recordValidatedRender(lease,"source",job.projectJson()); generated.confirmStored(lease,"source");
        if (register) generated.register(lease,"source");
        runtime.cancel(job.taskId(),"default",job.runId());
        runtime.acknowledgeCancellation(job.taskId(),"default",job.runId(),lease.worker(),lease.generation());
        assertThat(productionMapper.findPlan(job.taskId(),"default",automaticMapper.steps(job.taskId(),"default",job.runId()).getFirst().productionPlanId()).orElseThrow().status()).isEqualTo("READY");
        assertThat(automaticMapper.steps(job.taskId(),"default",job.runId())).allMatch(s -> "COMPLETED".equals(s.stepStatus()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"24,0,true","24,41,true","24,43,false","25,40,true","25,41,false","30,33,true","30,35,false","24,-41,true","24,-43,false"})
    void automaticProjectAdmissionChecksExactPlanDurationWithinOneFrame(int fps,int deltaMs,boolean allowed) throws Exception {
        var document=fixedDocument(); ((com.fasterxml.jackson.databind.node.ObjectNode)document.path("video")).put("fps",fps).put("frameCount",fps*2);
        var job=queuedAutomaticJob(task(),document,2000L+deltaMs);
        if (allowed) assertThat(fixedJobs.attach(job)).isNotNull();
        else { assertThatThrownBy(() -> fixedJobs.attach(job)).hasMessageContaining("工程实际时长与已确认目标不一致"); assertThat(fixedJobMapper.find(job.taskId(),"default",job.runId())).isEmpty(); }
    }

    @Test void automaticDurationMismatchIsRejectedAndManualPlanTargetRemainsAdvisory() throws Exception {
        var mismatched=queuedAutomaticJob(task(),fixedDocument(),1000L);
        assertThatThrownBy(() -> fixedJobs.attach(mismatched)).hasMessageContaining("工程实际时长与已确认目标不一致");
        var manual=queuedFixedJob(task(),fixedDocument());
        jdbc.update("UPDATE creator_production_plan SET target_duration_ms=1000 WHERE task_id=?",manual.taskId());
        assertThat(fixedJobs.attach(manual)).isNotNull();
    }

    @Test
    void automaticVersionRegistrationRequiresLocalAndStorageEvidence() throws Exception {
        var job = queuedAutomaticJob(task(), fixedDocument()); fixedJobs.attach(job); var lease = automaticLease(job);
        prepareAutomatic(lease, job.projectJson());
        assertThatThrownBy(() -> generated.confirmStored(lease, "source")).hasMessageContaining("本地制作证据");
        generated.recordValidatedRender(lease, "source", job.projectJson());
        var local = automaticMapper.steps(job.taskId(), "default", job.runId());
        assertThat(local).filteredOn(step -> "COMPLETED".equals(step.stepStatus())).hasSize(5);
        long revisions = jdbc.queryForObject("SELECT SUM(row_version) FROM creator_production_step WHERE task_id=? AND plan_id=?", Long.class, job.taskId(), local.getFirst().productionPlanId());
        generated.recordValidatedRender(lease, "source", job.projectJson());
        assertThat(jdbc.queryForObject("SELECT SUM(row_version) FROM creator_production_step WHERE task_id=? AND plan_id=?", Long.class, job.taskId(), local.getFirst().productionPlanId())).isEqualTo(revisions);
        assertThatThrownBy(() -> generated.register(lease, "source")).hasMessageContaining("存储完成证据");
        generated.confirmStored(lease, "source");
        var draft = generated.register(lease, "source"); assertThat(draft.status()).isEqualTo("UPLOADED");
        assertThat(automaticMapper.steps(job.taskId(), "default", job.runId())).allMatch(step -> "COMPLETED".equals(step.stepStatus()));
        assertThatThrownBy(() -> runtime.complete(job.taskId(), "default", job.runId(), lease.worker(), lease.generation(), "{\"done\":true}"))
                .hasMessageContaining("完整试映");
    }

    @Test
    void changedProjectCannotCompleteAutomaticSteps() throws Exception {
        var job = queuedAutomaticJob(task(), fixedDocument()); fixedJobs.attach(job); var lease = automaticLease(job); prepareAutomatic(lease, job.projectJson());
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(job.projectJson()); changed.put("seed", 8);
        assertThatThrownBy(() -> generated.recordValidatedRender(lease, "source", changed.toString())).hasMessageContaining("已验收工程不一致");
        assertThat(automaticMapper.steps(job.taskId(), "default", job.runId())).filteredOn(step -> "COMPLETED".equals(step.stepStatus())).hasSize(1);
    }

    @Test
    void cancelledAutomaticAttemptCannotCompleteSavedStep() throws Exception {
        var job = queuedAutomaticJob(task(), fixedDocument()); fixedJobs.attach(job); var lease = automaticLease(job);
        prepareAutomatic(lease, job.projectJson()); generated.recordValidatedRender(lease, "source", job.projectJson());
        runtime.cancel(job.taskId(), "default", job.runId());
        assertThatThrownBy(() -> generated.confirmStored(lease, "source")).hasMessageContaining("租约失效");
        assertThat(automaticMapper.steps(job.taskId(), "default", job.runId()).getLast().stepStatus()).isEqualTo("PENDING");
    }

    @Test
    void takeoverMustProduceItsOwnEvidenceAndCannotUseOldLease() throws Exception {
        var job = queuedAutomaticJob(task(), fixedDocument()); fixedJobs.attach(job); var oldLease = automaticLease(job);
        prepareAutomatic(oldLease, job.projectJson()); generated.recordValidatedRender(oldLease, "source", job.projectJson());
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE run_id=?", job.runId());
        var claimed = runtime.claim(job.taskId(), "default", job.runId(), "auto-replacement", 30).orElseThrow();
        var lease = new GeneratedDraftLease(job.taskId(), "default", job.runId(), "auto-replacement", claimed.leaseGeneration());
        assertThatThrownBy(() -> generated.confirmStored(oldLease, "source")).hasMessageContaining("租约失效");
        var json = new ObjectMapper(); var next = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(job.projectJson());
        next.put("attemptId", "next"); next.remove("inputHash");
        next.put("inputHash", nativeSha(json.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(json.convertValue(next, Map.class))));
        prepareAutomatic(lease, next.toString());
        assertThatThrownBy(() -> generated.confirmStored(lease, "next")).hasMessageContaining("本地制作证据");
        generated.recordValidatedRender(lease, "next", next.toString()); generated.confirmStored(lease, "next");
        var draft = generated.register(lease, "next");
        assertThat(generatedMapper.registered(job.taskId(), "default", job.runId()).orElseThrow().versionId()).isEqualTo(draft.versionId());
        assertThat(automaticMapper.steps(job.taskId(), "default", job.runId())).filteredOn(step -> !"APPROVED".equals(step.stepCode()))
                .allMatch(step -> "next".equals(step.attemptId()) && step.leaseGeneration() == 2);
    }

    private AutomaticProductionRequest automaticRequest(String task, String hash, long duration) {
        var confirmed = jdbc.queryForMap("SELECT session_id,confirmed_result_id FROM creator_workflow_session WHERE task_id=? ORDER BY id DESC LIMIT 1", task);
        return new AutomaticProductionRequest(confirmed.get("session_id").toString(), confirmed.get("confirmed_result_id").toString(),
                mapper.findDraftVideo(task, "default").map(DraftVideoRecord::versionId).orElse(null), "board", hash, duration);
    }

    private GeneratedDraftLease automaticLease(FixedProjectJob job) {
        var claimed = runtime.claim(job.taskId(), "default", job.runId(), "auto-test", 30).orElseThrow();
        return new GeneratedDraftLease(job.taskId(), "default", job.runId(), "auto-test", claimed.leaseGeneration());
    }

    @Test
    void mediaBlockedRunMustPauseItsExecutionAndRetainTheFailureReason() throws Exception {
        var job = queuedAutomaticJob(task(), fixedDocument());
        fixedJobs.attach(job);
        var lease = automaticLease(job);
        runtime.holdForUser(job.taskId(), "default", job.runId(), lease.worker(), lease.generation(), "PREFLIGHT_FAILED");

        // 从已受理的整片运行恢复外层检查点，不执行新的渲染或外部调用。
        var executions = executionMapper;
        String id = UUID.randomUUID().toString(), fingerprint = "f".repeat(64);
        long revision = revision(job.taskId());
        executions.insert(new ExecutionModels.Execution(id, job.taskId(), "default", "FULL", "ACTIVE", "FULL",
                "board", "board", "[]", null, null, null, cny("5"), null, revision, id, "a".repeat(64), fingerprint, null, "[]", null));
        executions.update(id, "ACTIVE", "FULL", "board", job.runId(), revision, null);
        var planner = mock(ExecutionPlanner.class);
        when(planner.fingerprint()).thenReturn(fingerprint);
        try (var validators = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var codec = new StoryboardCodec(new ObjectMapper(), validators.getValidator());
            var service = new ExecutionService(executions, planner, mapper, runtimeMapper, runtime, mock(StoryboardMapper.class),
                    codec, mock(CreationVoiceService.class), mock(CreationVideoService.class), automaticPlans, mediaLinks, creationPackages);
            ExecutionService.Work next = transactions.execute(status -> service.advance(job.taskId(), "default", id));
            assertThat(next).isNull();
            var view = service.get(job.taskId(), "default", id);
            assertThat(view.status()).isEqualTo("WAITING_USER");
            assertThat(view.errorCode()).isEqualTo("PREFLIGHT_FAILED");
            assertThat(view.message()).isNotBlank();
            assertThat(view.run().stage()).isEqualTo("MEDIA_BLOCKED");
            assertThat(runtime.snapshot(job.taskId(), "default").activeRun().runId()).isEqualTo(job.runId());
            ExecutionService.Work repeated = transactions.execute(status -> service.advance(job.taskId(), "default", id));
            assertThat(repeated).isNull();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void cancelledLostSampleReleasesTheWorkspaceEvenWithAnAlreadyOverwrittenStage(boolean legacyStage) {
        String task=task(),id=UUID.randomUUID().toString();
        runtime.initialize(task,"default","sample-budget",revision(task),cny("20"));
        var run=runtime.startPreparation(task,"default","PREPARE","sample-run",revision(task),
                "{\"purpose\":\"SAMPLE\",\"executionId\":\""+id+"\"}",cny("0"));
        runtime.claim(task,"default",run.runId(),"lost-sample-worker",30).orElseThrow();
        executionMapper.insert(new ExecutionModels.Execution(id,task,"default","SAMPLE","ACTIVE","SAMPLE",
                "board","board","[\"s1\"]",null,null,null,cny("0"),null,revision(task),id,"a".repeat(64),"f".repeat(64),null,"[]",null));
        executionMapper.update(id,"ACTIVE","SAMPLE","board",run.runId(),revision(task),null);
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE run_id=?",run.runId());
        var boardMapper=mock(StoryboardMapper.class);var renderer=mock(FixedProjectRenderService.class);var storage=mock(ObjectStorageService.class);
        try(var validators=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var codec=new StoryboardCodec(new ObjectMapper(),validators.getValidator());
            var service=new ExecutionService(executionMapper,mock(ExecutionPlanner.class),mapper,runtimeMapper,runtime,boardMapper,
                    codec,mock(CreationVoiceService.class),mock(CreationVideoService.class),automaticPlans,mediaLinks,creationPackages);
            ExecutionModels.View cancelled=transactions.execute(status->service.cancel(task,"default",id));
            assertThat(cancelled.status()).isEqualTo("CANCEL_REQUESTED");
            if(legacyStage)executionMapper.update(id,"CANCEL_REQUESTED","CANCEL_REQUESTED","board",run.runId(),revision(task),null);
            else assertThat(cancelled.stage()).isEqualTo("SAMPLE");
            ExecutionService.Work work=transactions.execute(status->service.advance(task,"default",id));
            assertThat(work).isNotNull();assertThat(work.action()).isEqualTo("SAMPLE");
            try(var samples=new ExecutionSampleRenderer(runtime,service,executionMapper,boardMapper,mock(StoryboardPersistence.class),codec,
                    mock(StoryboardCompiler.class),mock(StoryboardProjectFiles.class),renderer,storage,new ObjectStorageProperties())) {
                samples.render(work.execution());
            }
            ExecutionService.Work finished=transactions.execute(status->service.advance(task,"default",id));
            assertThat(finished).isNull();assertThat(service.get(task,"default",id).status()).isEqualTo("CANCELLED");
            assertThat(runtime.getRun(task,"default",run.runId()).status()).isEqualTo("CANCELLED");
            assertThat(runtime.snapshot(task,"default").activeRun()).isNull();verifyNoInteractions(renderer,storage,boardMapper);
            var next=runtime.startPreparation(task,"default","PREPARE","next-sample",revision(task),"{\"purpose\":\"SAMPLE\"}",cny("0"));
            assertThat(next.status()).isEqualTo("QUEUED");runtime.cancel(task,"default",next.runId());
        }
    }

    private GeneratedDraftAttempt prepareAutomatic(GeneratedDraftLease lease, String document) throws Exception {
        var json = new ObjectMapper(); var project = json.readTree(document);
        var input = json.readTree(runtimeMapper.run(lease.taskId(), lease.ownerId(), lease.runId()).orElseThrow().requestJson());
        return generated.prepare(lease, project.path("attemptId").asText(), project.path("inputHash").asText(), input.path("renderContentHash").asText(),
                project.path("storyboardId").asText(), "fixture-bucket", generatedFiles());
    }

    private record ProbeFixture(FixedProjectJob job,GeneratedDraftLease lease,DraftVideoRecord draft) { }

    private ProbeFixture registeredProbeFixture() throws Exception {
        var job=queuedAutomaticJob(task(),fixedDocument()); fixedJobs.attach(job); var lease=automaticLease(job);
        prepareAutomatic(lease,job.projectJson()); generated.recordValidatedRender(lease,"source",job.projectJson()); generated.confirmStored(lease,"source");
        return new ProbeFixture(job,lease,generated.register(lease,"source"));
    }

    private MediaProbeResult expectedProbeResult() {
        return new MediaProbeResult(2000,1280,720,new BigDecimal("24.000000"),"h264","aac",true);
    }

    @Test void generatedProbePersistsExactVersionAndReusesReadyWithoutNetwork() throws Exception {
        var fixture=registeredProbeFixture(); var properties=new CreatorMediaProperties(); properties.setEnabled(true);
        var storage=mock(ObjectStorageService.class); var ffprobe=mock(FfprobeMediaProbeService.class);
        when(storage.presignGetObject(anyString(),anyString(),any())).thenReturn(new com.link.linkagent.creator.media.storage.PresignedObjectRead("https://media.invalid/source.mp4",java.time.Instant.now().plusSeconds(300)));
        when(ffprobe.probe(anyString(),any())).thenReturn(expectedProbeResult());
        var service=new GeneratedDraftProbeService(properties,storage,ffprobe,generatedProbes);
        assertThat(service.probe(fixture.lease(),fixture.draft().versionId(),() -> false).status()).isEqualTo("READY_FOR_REVIEW");
        assertThat(service.probe(fixture.lease(),fixture.draft().versionId(),() -> false).durationMs()).isEqualTo(2000);
        verify(storage,times(1)).presignGetObject(anyString(),anyString(),any()); verify(ffprobe,times(1)).probe(anyString(),any());
        assertThat(runtimeMapper.run(fixture.job().taskId(),"default",fixture.job().runId()).orElseThrow().status()).isEqualTo("RUNNING");
        assertThatThrownBy(() -> runtime.complete(fixture.job().taskId(),"default",fixture.job().runId(),fixture.lease().worker(),fixture.lease().generation(),"{\"done\":true}")).hasMessageContaining("完整试映");
    }

    @Test void generatedProbeTakeoverRejectsBothLateSuccessAndLateFailure() throws Exception {
        var fixture=registeredProbeFixture(); var old=generatedProbes.begin(fixture.lease(),fixture.draft().versionId());
        assertThatThrownBy(() -> generatedProbes.begin(fixture.lease(),fixture.draft().versionId())).hasMessageContaining("已经领取");
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE run_id=?",fixture.job().runId());
        var next=runtime.claim(fixture.job().taskId(),"default",fixture.job().runId(),"probe-replacement",30).orElseThrow();
        var lease=new GeneratedDraftLease(next.taskId(),next.ownerId(),next.runId(),"probe-replacement",next.leaseGeneration());
        var current=generatedProbes.begin(lease,fixture.draft().versionId()); assertThat(current.attemptId()).isNotEqualTo(old.attemptId());
        assertThatThrownBy(() -> generatedProbes.complete(fixture.lease(),fixture.draft().versionId(),old.attemptId(),expectedProbeResult())).hasMessageContaining("租约失效");
        assertThatThrownBy(() -> generatedProbes.fail(fixture.lease(),fixture.draft().versionId(),old.attemptId())).hasMessageContaining("租约失效");
        assertThat(generatedProbes.complete(lease,fixture.draft().versionId(),current.attemptId(),expectedProbeResult()).status()).isEqualTo("READY_FOR_REVIEW");
    }

    @Test void generatedProbeExcludesManualClaimAndLazyRecoveryUntilCancellation() throws Exception {
        var fixture=registeredProbeFixture(); String task=fixture.job().taskId(),version=fixture.draft().versionId();
        assertThat(mapper.claimDraftVideoProbe(task,"default",version,"manual-token")).isZero();
        var claim=generatedProbes.begin(fixture.lease(),version);
        jdbc.update("UPDATE creator_draft_video SET update_time=TIMESTAMPADD(MINUTE,-5,NOW()) WHERE version_id=?",version);
        var properties=new CreatorMediaProperties(); properties.setEnabled(true);
        var recovery=new DraftVideoProbeRecoveryService(properties,mapper);
        assertThat(recovery.recover(mapper.findDraftVideoByVersion(task,"default",version).orElseThrow()).probeAttemptId()).isEqualTo(claim.attemptId());
        runtime.cancel(task,"default",fixture.job().runId());
        assertThatThrownBy(() -> generatedProbes.complete(fixture.lease(),version,claim.attemptId(),expectedProbeResult())).hasMessageContaining("取消");
        runtime.acknowledgeCancellation(task,"default",fixture.job().runId(),fixture.lease().worker(),fixture.lease().generation());
        assertThat(mapper.findDraftVideoByVersion(task,"default",version).orElseThrow().status()).isEqualTo("PROBE_FAILED");
        assertThat(mapper.claimDraftVideoProbe(task,"default",version,"manual-after-cancel")).isEqualTo(1);
    }

    @Test void cancellationTakeoverClearsAnOlderGenerationsProbeToken() throws Exception {
        var fixture=registeredProbeFixture(); generatedProbes.begin(fixture.lease(),fixture.draft().versionId());
        runtime.cancel(fixture.job().taskId(),"default",fixture.job().runId());
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE run_id=?",fixture.job().runId());
        var next=runtime.claim(fixture.job().taskId(),"default",fixture.job().runId(),"cancel-replacement",30).orElseThrow();
        runtime.acknowledgeCancellation(next.taskId(),next.ownerId(),next.runId(),"cancel-replacement",next.leaseGeneration());
        var draft=mapper.findDraftVideoByVersion(next.taskId(),next.ownerId(),fixture.draft().versionId()).orElseThrow();
        assertThat(draft.status()).isEqualTo("PROBE_FAILED"); assertThat(draft.probeAttemptId()).isNull();
    }

    @Test void actualSourceMismatchCannotBeSavedAsReady() throws Exception {
        var fixture=registeredProbeFixture(); var claim=generatedProbes.begin(fixture.lease(),fixture.draft().versionId());
        var wrong=new MediaProbeResult(4000,1280,720,new BigDecimal("24"),"h264","aac",true);
        assertThatThrownBy(() -> generatedProbes.complete(fixture.lease(),fixture.draft().versionId(),claim.attemptId(),wrong)).hasMessageContaining("实测信息");
        var draft=mapper.findDraftVideoByVersion(fixture.job().taskId(),"default",fixture.draft().versionId()).orElseThrow();
        assertThat(draft.status()).isEqualTo("PROBING"); assertThat(draft.durationMs()).isNull();
        generatedProbes.fail(fixture.lease(),fixture.draft().versionId(),claim.attemptId());
        assertThat(mapper.findDraftVideoByVersion(fixture.job().taskId(),"default",fixture.draft().versionId()).orElseThrow().status()).isEqualTo("PROBE_FAILED");
    }

    @Test void expiredLeaseDuringMetadataWriteRollsBackReadyAndMetadata() throws Exception {
        var fixture=registeredProbeFixture(); var claim=generatedProbes.begin(fixture.lease(),fixture.draft().versionId());
        String trigger="probe_expiry_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON creator_draft_video FOR EACH ROW BEGIN IF NEW.version_id='"+fixture.draft().versionId()+"' AND NEW.status='READY_FOR_REVIEW' THEN DO SLEEP(2.2); END IF; END");
        try {
            jdbc.update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,2,NOW(6)) WHERE run_id=?",fixture.job().runId());
            assertThatThrownBy(() -> generatedProbes.complete(fixture.lease(),fixture.draft().versionId(),claim.attemptId(),expectedProbeResult())).hasMessageContaining("租约已失效");
            var draft=mapper.findDraftVideoByVersion(fixture.job().taskId(),"default",fixture.draft().versionId()).orElseThrow();
            assertThat(draft.status()).isEqualTo("PROBING"); assertThat(draft.durationMs()).isNull(); assertThat(draft.probeAttemptId()).isEqualTo(claim.attemptId());
        } finally { jdbc.execute("DROP TRIGGER "+trigger); }
    }

    @Test
    void fixedProjectAttachmentIsDurableIdempotentAndRejectsDifferentContent() throws Exception {
        var job = queuedFixedJob(task(), fixedDocument());
        var saved = fixedJobs.attach(job);
        assertThat(fixedJobs.attach(job)).isEqualTo(saved);
        assertThat(fixedJobMapper.find(job.taskId(), "default", job.runId())).contains(saved);
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(job.projectJson());
        changed.put("seed", 9);
        assertThatThrownBy(() -> fixedJobs.attach(new FixedProjectJob(job.runId(), job.taskId(), "default", job.sourceAttemptId(), changed.toString())))
                .hasMessageContaining("冻结其他工程");
        assertThat(runtimeMapper.run(job.taskId(), "default", job.runId()).orElseThrow().status()).isEqualTo("QUEUED");
    }

    @Test
    void fixedProjectCannotAttachWhenContentDisagreesWithProductionApproval() throws Exception {
        var job = queuedFixedJob(task(), fixedDocument());
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(job.projectJson());
        changed.put("seed", 8);
        assertThatThrownBy(() -> fixedJobs.attach(new FixedProjectJob(job.runId(), job.taskId(), "default", "source", changed.toString())))
                .hasMessageContaining("授权不一致");
        assertThat(fixedJobMapper.find(job.taskId(), "default", job.runId())).isEmpty();
    }

    @Test
    void fixedProjectCannotBeAttachedAfterFirstClaim() throws Exception {
        var job = queuedFixedJob(task(), fixedDocument());
        runtime.claim(job.taskId(), "default", job.runId(), "already-started", 5).orElseThrow();
        assertThatThrownBy(() -> fixedJobs.attach(job)).hasMessageContaining("尚未领取");
    }

    @Test
    void onlyExplicitlyAttachedAndStillActiveFixedProjectsAreCandidates() throws Exception {
        var absent = queuedFixedJob(task(), fixedDocument());
        var available = queuedFixedJob(task(), fixedDocument()); fixedJobs.attach(available);
        assertThat(fixedJobMapper.candidates()).extracting(FixedProjectJob::runId).contains(available.runId()).doesNotContain(absent.runId());
        runtime.cancel(available.taskId(), "default", available.runId());
        assertThat(fixedJobMapper.candidates()).extracting(FixedProjectJob::runId).doesNotContain(available.runId());
    }

    @Test
    void twoWorkersCannotRenderSameQueuedProjectTwice() throws Exception {
        var job = queuedFixedJob(task(), fixedDocument()); fixedJobs.attach(job);
        var files = mock(FixedProjectJobFiles.class);
        var renderer = mock(com.link.linkagent.creator.creation.render.FixedProjectRenderService.class);
        var drafts = mock(com.link.linkagent.creator.creation.service.GeneratedDraftService.class);
        when(files.prepare(any(), any(), any())).thenReturn(new com.link.linkagent.creator.creation.render.FixedProjectRenderService.Request(
                java.nio.file.Path.of("not-read"), job.runId(), "attempt", "board", "a".repeat(64)));
        when(renderer.render(any(), any())).thenThrow(new com.link.linkagent.creator.creation.render.RenderExecutionException("CONTROLLED_TEST_FAILURE", "仅验证领取"));
        try (var first = fixedWorker(files, renderer, drafts); var second = fixedWorker(files, renderer, drafts);
             var callers = Executors.newFixedThreadPool(2)) {
            Future<?> one = callers.submit(first::poll), two = callers.submit(second::poll); one.get(3, TimeUnit.SECONDS); two.get(3, TimeUnit.SECONDS);
            awaitFixedStatus(job, "FAILED", 5);
        }
        verify(renderer, times(1)).render(any(), any()); verifyNoInteractions(drafts);
        assertThat(runtimeMapper.run(job.taskId(), "default", job.runId()).orElseThrow().attemptCount()).isEqualTo(1);
        assertThat(runtime.snapshot(job.taskId(), "default").activeRun()).isNull();
    }

    @Test
    void cancelledDependencyIsAcknowledgedWithoutTouchingSourceFiles() throws Exception {
        var job = queuedFixedJob(task(), fixedDocument()); fixedJobs.attach(job);
        var claimed = runtime.claim(job.taskId(), "default", job.runId(), "before-wait", 5).orElseThrow();
        runtime.waitForDependency(job.taskId(), "default", job.runId(), "before-wait", claimed.leaseGeneration(), "exact-version");
        runtime.cancel(job.taskId(), "default", job.runId());
        var files = mock(FixedProjectJobFiles.class); var renderer = mock(com.link.linkagent.creator.creation.render.FixedProjectRenderService.class);
        var drafts = mock(com.link.linkagent.creator.creation.service.GeneratedDraftService.class);
        try (var worker = fixedWorker(files, renderer, drafts)) { worker.poll(); awaitFixedStatus(job, "CANCELLED", 5); }
        verifyNoInteractions(files, renderer, drafts);
    }

    @Test
    void registeredCrashRecoveryKeepsOneVersionWithoutOriginalInputDirectory() throws Exception {
        var job = queuedFixedJob(task(), fixedDocument()); fixedJobs.attach(job);
        var claimed = runtime.claim(job.taskId(), "default", job.runId(), "crashed-after-register", 5).orElseThrow();
        var lease = new GeneratedDraftLease(job.taskId(), "default", job.runId(), "crashed-after-register", claimed.leaseGeneration());
        String contentHash = new ObjectMapper().readTree(claimed.requestJson()).path("renderContentHash").asText();
        generated.prepare(lease, "before-crash", "a".repeat(64), contentHash, "board", "fixture-bucket", generatedFiles());
        var draft = generated.register(lease, "before-crash");
        jdbc.update("UPDATE creator_creation_run SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE run_id=?", job.runId());
        var files = mock(FixedProjectJobFiles.class); var renderer = mock(com.link.linkagent.creator.creation.render.FixedProjectRenderService.class);
        var drafts = mock(com.link.linkagent.creator.creation.service.GeneratedDraftService.class);
        try (var worker = fixedWorker(files, renderer, drafts)) { worker.poll(); awaitFixedStatus(job, "WAITING_DEPENDENCY", 5); }
        var restored = runtimeMapper.run(job.taskId(), "default", job.runId()).orElseThrow();
        assertThat(restored.leaseGeneration()).isEqualTo(2); assertThat(restored.dependencyId()).isEqualTo(draft.versionId());
        assertThat(mapper.findMediaWorkspace(job.taskId(), "default").orElseThrow().nextVersionNo()).isEqualTo(2);
        verifyNoInteractions(files, renderer, drafts);
    }

    @Test
    void repeatedCrashesStopAtConfiguredAttemptLimit() throws Exception {
        var job = queuedFixedJob(task(), fixedDocument()); fixedJobs.attach(job);
        for (int index = 0; index < 3; index++) {
            runtime.claim(job.taskId(), "default", job.runId(), "crashed-" + index, 5).orElseThrow();
            jdbc.update("UPDATE creator_creation_run SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE run_id=?", job.runId());
        }
        var files = mock(FixedProjectJobFiles.class); var renderer = mock(com.link.linkagent.creator.creation.render.FixedProjectRenderService.class);
        var drafts = mock(com.link.linkagent.creator.creation.service.GeneratedDraftService.class);
        try (var worker = fixedWorker(files, renderer, drafts)) { worker.poll(); awaitFixedStatus(job, "FAILED", 5); }
        assertThat(runtimeMapper.run(job.taskId(), "default", job.runId()).orElseThrow().errorCode()).isEqualTo("ATTEMPTS_EXHAUSTED");
        verifyNoInteractions(files, renderer, drafts);
    }

    @org.junit.jupiter.api.AfterEach
    void closeFixedJobFixtures() {
        for (var job : fixedJobsToClean) {
            var run = runtimeMapper.run(job.taskId(), "default", job.runId()).orElseThrow();
            if (!List.of("FAILED", "CANCELLED", "COMPLETED").contains(run.status())) {
                runtime.cancel(job.taskId(), "default", job.runId());
                if ("CANCEL_REQUESTED".equals(runtimeMapper.run(job.taskId(), "default", job.runId()).orElseThrow().status())) {
                    jdbc.update("UPDATE creator_creation_run SET lease_expires_at=NULL WHERE run_id=?", job.runId());
                    var cleanup = runtime.claim(job.taskId(), "default", job.runId(), "fixed-test-cleanup", 5).orElseThrow();
                    runtime.acknowledgeCancellation(job.taskId(), "default", job.runId(), "fixed-test-cleanup", cleanup.leaseGeneration());
                }
            }
        }
    }

    // 这些简化工程仅用于 SQL 与租约检查，可播放媒体在下面的真实渲染测试中单独验证。
    private com.fasterxml.jackson.databind.node.ObjectNode fixedDocument() throws Exception {
        return (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree("""
                {"schemaVersion":1,"storyboardId":"board","seed":7,"assets":[],"video":{"width":1280,"height":720,"fps":24,"frameCount":48}}
                """);
    }

    private FixedProjectJob queuedFixedJob(String task, com.fasterxml.jackson.databind.node.ObjectNode document) throws Exception {
        var json = new ObjectMapper(); var sorted = json.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        var content = document.deepCopy(); content.remove(List.of("runId", "attemptId", "inputHash"));
        String contentHash = nativeSha(sorted.writeValueAsBytes(json.convertValue(content, Map.class)));
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(fullInput(task, contentHash));
        input.put("storyboardId", document.path("storyboardId").asText());
        runtime.initialize(task, "default", "fixed-budget", revision(task), cny("20"));
        var run = runtime.startProduction(task, "default", "fixed-run", revision(task), input.toString(), cny("0"));
        document.put("runId", run.runId()).put("attemptId", "source"); document.remove("inputHash");
        document.put("inputHash", nativeSha(sorted.writeValueAsBytes(json.convertValue(document, Map.class))));
        var job = new FixedProjectJob(run.runId(), task, "default", "source", document.toString()); fixedJobsToClean.add(job); return job;
    }

    private FixedProjectJob queuedAutomaticJob(String task, com.fasterxml.jackson.databind.node.ObjectNode document) throws Exception {
        return queuedAutomaticJob(task,document,null);
    }

    private FixedProjectJob queuedAutomaticJob(String task, com.fasterxml.jackson.databind.node.ObjectNode document, Long requestedDurationMs) throws Exception {
        var json = new ObjectMapper(); var sorted = json.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        var content = document.deepCopy(); content.remove(List.of("runId", "attemptId", "inputHash"));
        String contentHash = nativeSha(sorted.writeValueAsBytes(json.convertValue(content, Map.class)));
        confirmedPlan(task);
        var confirmed = jdbc.queryForMap("SELECT session_id,confirmed_result_id FROM creator_workflow_session WHERE task_id=? ORDER BY id DESC LIMIT 1", task);
        runtime.initialize(task, "default", "auto-budget", revision(task), cny("20"));
        long duration = requestedDurationMs != null ? requestedDurationMs : Math.round(document.path("video").path("frameCount").asDouble() * 1000 / document.path("video").path("fps").asInt());
        var request = new AutomaticProductionRequest(confirmed.get("session_id").toString(), confirmed.get("confirmed_result_id").toString(),
                mapper.findDraftVideo(task, "default").map(DraftVideoRecord::versionId).orElse(null), document.path("storyboardId").asText(), contentHash, duration);
        var run = automaticPlans.start(task, "default", "automatic-fixed", revision(task), request, cny("0"));
        document.put("runId", run.runId()).put("attemptId", "source"); document.remove("inputHash");
        document.put("inputHash", nativeSha(sorted.writeValueAsBytes(json.convertValue(document, Map.class))));
        var job = new FixedProjectJob(run.runId(), task, "default", "source", document.toString()); fixedJobsToClean.add(job); return job;
    }

    private FixedProjectWorker fixedWorker(FixedProjectJobFiles files,
            com.link.linkagent.creator.creation.render.FixedProjectRenderService renderer,
            com.link.linkagent.creator.creation.service.GeneratedDraftService drafts) {
        return fixedWorker(files,renderer,drafts,protocolProbe());
    }

    private GeneratedDraftProbeService protocolProbe() {
        var probe=mock(GeneratedDraftProbeService.class);
        when(probe.probe(any(),anyString(),any())).thenAnswer(call -> {
            GeneratedDraftLease lease=call.getArgument(0);
            return mapper.findDraftVideoByVersion(lease.taskId(),lease.ownerId(),call.getArgument(1)).orElseThrow();
        });
        return probe;
    }

    private FixedProjectWorker fixedWorker(FixedProjectJobFiles files,
            com.link.linkagent.creator.creation.render.FixedProjectRenderService renderer,
            com.link.linkagent.creator.creation.service.GeneratedDraftService drafts,GeneratedDraftProbeService probe) {
        var properties = new CreationWorkerProperties(); properties.setEnabled(true); properties.setLeaseSeconds(5); properties.setHeartbeatSeconds(1);
        return new FixedProjectWorker(properties, fixedJobMapper, runtime, files, renderer, drafts, generated, probe, creationMedia);
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @org.springframework.scheduling.annotation.EnableScheduling
    static class FixedWorkerScheduling { }

    private org.springframework.context.annotation.AnnotationConfigApplicationContext scheduledFixedWorker(FixedProjectJobFiles files,
            com.link.linkagent.creator.creation.render.FixedProjectRenderService renderer,
            com.link.linkagent.creator.creation.service.GeneratedDraftService drafts) {
        return scheduledFixedWorker(files,renderer,drafts,protocolProbe());
    }

    private org.springframework.context.annotation.AnnotationConfigApplicationContext scheduledFixedWorker(FixedProjectJobFiles files,
            com.link.linkagent.creator.creation.render.FixedProjectRenderService renderer,
            com.link.linkagent.creator.creation.service.GeneratedDraftService drafts,GeneratedDraftProbeService probe) {
        var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("fixed-worker-test",
                Map.of("creator.media.enabled", "true", "creator.creation.renderer.enabled", "true",
                        "creator.creation.worker.enabled", "true", "creator.creation.worker.poll-interval-ms", "100")));
        var properties = new CreationWorkerProperties(); properties.setEnabled(true); properties.setLeaseSeconds(5); properties.setHeartbeatSeconds(1);
        context.registerBean(CreationWorkerProperties.class, () -> properties);
        context.registerBean(FixedProjectJobMapper.class, () -> fixedJobMapper);
        context.registerBean(CreationRuntimeService.class, () -> runtime);
        context.registerBean(FixedProjectJobFiles.class, () -> files);
        context.registerBean(com.link.linkagent.creator.creation.render.FixedProjectRenderService.class, () -> renderer);
        context.registerBean(com.link.linkagent.creator.creation.service.GeneratedDraftService.class, () -> drafts);
        context.registerBean(GeneratedDraftPersistenceService.class, () -> generated);
        context.registerBean(GeneratedDraftProbeService.class, () -> probe);
        context.registerBean(com.link.linkagent.creator.creation.service.CreationMediaService.class, () -> creationMedia);
        context.register(FixedWorkerScheduling.class, FixedProjectWorker.class); context.refresh(); return context;
    }

    private void awaitFixedStatus(FixedProjectJob job, String status, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            var run = runtimeMapper.run(job.taskId(), "default", job.runId()).orElseThrow();
            if (status.equals(run.status())) return;
            if ("FAILED".equals(run.status())) throw new AssertionError("固定工程失败：" + run.errorCode());
            Thread.sleep(25);
        }
        throw new AssertionError("固定工程未到达预期状态：" + status);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={1,2})
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="LINKAGENT_GENERATED_NATIVE", matches="true")
    void realRenderedVideoSurvivesPrivateStorageRegistrationProbeAndDeletion(int schemaVersion, @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var json = new ObjectMapper();
        java.nio.file.Path source = java.nio.file.Path.of(System.getenv("LINKAGENT_RENDERER_SAMPLE_ROOT"));
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(source.resolve("input.json").toFile());
        java.nio.file.Path clipSource = root.resolve("source-clip.mp4");
        if (schemaVersion == 2) {
            var scene = (com.fasterxml.jackson.databind.node.ObjectNode) document.path("scenes").get(0);
            int fps = document.path("video").path("fps").asInt();
            long clipMs = Math.round((scene.path("endFrame").asLong() - scene.path("startFrame").asLong()) * 1000.0 / fps);
            var maker = new ProcessBuilder("ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i",
                    "color=c=blue:s=320x180:r=" + fps + ":d=" + ((clipMs + 1500) / 1000.0),
                    "-an", "-c:v", "libx264", "-threads", "2", "-movflags", "+faststart", "-y", clipSource.toString())
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            assertThat(maker.waitFor(15, TimeUnit.SECONDS)).isTrue(); assertThat(maker.exitValue()).isZero();
            document.put("schemaVersion", 2);
            ((com.fasterxml.jackson.databind.node.ArrayNode) document.path("assets")).addObject().put("id", "video-clip")
                    .put("path", "source-clip.mp4").put("mime", "video/mp4").put("sha256", nativeSha(java.nio.file.Files.readAllBytes(clipSource)));
            scene.put("layout", "VIDEO_CLIP"); scene.remove("imageAssetId"); scene.putArray("body");
            scene.putObject("clip").put("assetId", "video-clip").put("sourceStartMs", 1000).put("sourceEndMs", 1000 + clipMs)
                    .put("fit", "CONTAIN").put("audioMode", "MUTE").put("gainDb", 0);
        }
        String task = task(); var job = queuedAutomaticJob(task, document);
        String attemptId = job.sourceAttemptId();
        java.nio.file.Path attempt = java.nio.file.Files.createDirectories(root.resolve(job.runId()).resolve(attemptId));
        for (var asset : document.path("assets")) {
            String name = asset.path("path").asText(); java.nio.file.Path target = attempt.resolve(name).normalize();
            assertThat(target.startsWith(attempt)).isTrue();
            java.nio.file.Files.createDirectories(target.getParent()); java.nio.file.Files.copy(schemaVersion == 2 && name.equals("source-clip.mp4") ? clipSource : source.resolve(name), target);
        }
        String inputHash = document.path("inputHash").asText();
        java.nio.file.Path inputFile = attempt.resolve("input.json"); json.writeValue(inputFile.toFile(), document);
        var rendererProperties = new com.link.linkagent.creator.creation.render.FixedProjectRendererProperties();
        rendererProperties.setEnabled(true); rendererProperties.setPythonPath("python"); rendererProperties.setModuleRoot(System.getenv("LINKAGENT_RENDERER_MODULE_ROOT"));
        rendererProperties.setWorkRoot(root.toString()); rendererProperties.setTimeout(Duration.ofSeconds(120));
        var renderer = new com.link.linkagent.creator.creation.render.FixedProjectRenderService(rendererProperties, json);
        var request = new com.link.linkagent.creator.creation.render.FixedProjectRenderService.Request(inputFile, job.runId(), attemptId,
                document.path("storyboardId").asText(), inputHash);
        var jobFiles = new FixedProjectJobFiles(rendererProperties, json);
        fixedJobs.attach(jobFiles.freeze(task, "default", request));
        try (var store = new GenericContainer<>("rustfs/rustfs:latest").withEnv("RUSTFS_ACCESS_KEY", "isolated-generated")
                .withEnv("RUSTFS_SECRET_KEY", "isolated-generated-secret").withCommand("rustfs", "/data")
                .withCreateContainerCmdModifier(cmd -> cmd.withUser("0")).withExposedPorts(9000)
                .withLabels(Map.of("linkagent.scope", "creation-s3b")).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)))) {
            store.start(); String endpoint = "http://" + store.getHost() + ":" + store.getMappedPort(9000);
            var properties = new ObjectStorageProperties(); properties.setInternalEndpoint(endpoint); properties.setBrowserEndpoint(endpoint);
            properties.setProviderEndpoint(endpoint); properties.setAccessKey("isolated-generated"); properties.setSecretKey("isolated-generated-secret");
            properties.setRegion("us-east-1"); properties.setBucket("generated-video-test"); properties.setPathStyleAccess(true);
            var config = new com.link.linkagent.creator.media.config.ObjectStorageConfiguration();
            var s3Config = config.mediaS3Configuration(properties); var credentials = config.mediaS3CredentialsProvider(properties);
            try (var client = config.mediaS3Client(properties, s3Config, credentials);
                 var presigner = config.mediaS3Presigner(properties, s3Config, credentials)) {
                client.createBucket(b -> b.bucket(properties.getBucket()));
                var storage = spy(new com.link.linkagent.creator.media.storage.S3ObjectStorageService(client, presigner, presigner, properties));
                var service = new com.link.linkagent.creator.creation.service.GeneratedDraftService(renderer, generated, storage, properties, json);
                var mediaProperties = new CreatorMediaProperties(); mediaProperties.setEnabled(true);
                var ffprobe = new FfprobeMediaProbeService(mediaProperties, json);
                try {
                var autoProbe = new GeneratedDraftProbeService(mediaProperties,storage,ffprobe,generatedProbes);
                try (var context = scheduledFixedWorker(jobFiles, renderer, service,autoProbe)) {
                    awaitFixedStatus(job, "WAITING_DEPENDENCY", 150);
                    assertThat(context.getBean(FixedProjectWorker.class)).isNotNull();
                }
                var registered = generatedMapper.registered(task, "default", job.runId()).orElseThrow();
                var draft = mapper.findDraftVideoByVersion(task, "default", registered.versionId()).orElseThrow();
                assertThat(draft.status()).isEqualTo("READY_FOR_REVIEW");
                assertThat(draft.durationMs()).isBetween(84100L,84240L);
                var automaticSteps = automaticMapper.steps(task, "default", job.runId());
                assertThat(automaticSteps).hasSize(6).allMatch(step -> "COMPLETED".equals(step.stepStatus()) && step.evidenceJson() != null);
                assertThat(automaticSteps).filteredOn(step -> !"APPROVED".equals(step.stepCode()))
                        .allMatch(step -> step.leaseGeneration() == 1 && registered.attemptId().equals(step.attemptId()));
                assertThat(client.listObjectsV2(b -> b.bucket(properties.getBucket())).contents()).hasSize(8);
                var waiting = runtimeMapper.run(task, "default", job.runId()).orElseThrow();
                assertThat(waiting.leaseGeneration()).isEqualTo(1); assertThat(waiting.completedAt()).isNull();
                assertThat(waiting.dependencyId()).isEqualTo(draft.versionId());
                // 移走本测试创建的完整工作目录，接管仍须从登记事实恢复同一版本。
                java.nio.file.Files.move(root.resolve(job.runId()), root.resolve("removed-workdir"));
                runtime.wake(task, "default", job.runId(), waiting.runRevision());
                // 已交接的运行由真实媒体 worker 接回；子任务受理用受控边界，不发出供应商请求。
                var downstream = mock(MediaProcessingService.class);
                var accepted = mock(com.link.linkagent.creator.media.processing.model.MediaProcessingJobResponse.class);
                when(accepted.jobId()).thenReturn("native-processing-" + job.runId());
                when(accepted.status()).thenReturn("QUEUED");
                doAnswer(call -> {
                    ((com.link.linkagent.creator.media.access.InternalMediaAcceptance) call.getArgument(4))
                            .verify(task, "default", draft.versionId());
                    return accepted;
                }).when(downstream).createJob(any(), any(), any(), any(), any());
                when(downstream.getCurrentJob(any(), any(), any())).thenReturn(accepted);
                var recoveryMediaProperties = new com.link.linkagent.creator.creation.service.CreationMediaProperties();
                recoveryMediaProperties.setEnabled(true);
                try (var worker = new com.link.linkagent.creator.creation.service.CreationMediaWorker(recoveryMediaProperties,
                        scopedMediaCandidates(job.runId()), creationPackages, generatedMapper, runtime, downstream, mock(PreflightReviewService.class),
                        mock(com.link.linkagent.creator.creation.service.CreationPackageService.class), creationMedia)) {
                    worker.poll(); awaitFixedStatus(job, "WAITING_DEPENDENCY", 5);
                }
                assertThat(runtimeMapper.run(task, "default", job.runId()).orElseThrow().leaseGeneration()).isEqualTo(2);
                assertThat(mapper.findMediaWorkspace(task, "default").orElseThrow().nextVersionNo()).isEqualTo(2);
                assertThat(client.listObjectsV2(b -> b.bucket(properties.getBucket())).contents()).hasSize(8);
                assertThat(runtime.snapshot(task, "default").executionAvailable()).isFalse();
                long observedDuration = draft.durationMs();
                verify(storage,times(1)).presignGetObject(eq(draft.bucketName()),eq(draft.objectKey()),any());
                assertThat(readyGate.inspectPostPublishReadiness(task, "default", "发布").ready()).isFalse();
                runtime.cancel(task, "default", job.runId());
                try (var worker = new com.link.linkagent.creator.creation.service.CreationMediaWorker(recoveryMediaProperties,
                        scopedMediaCandidates(job.runId()), creationPackages, generatedMapper, runtime, downstream, mock(PreflightReviewService.class),
                        mock(com.link.linkagent.creator.creation.service.CreationPackageService.class), creationMedia)) {
                    worker.poll(); awaitFixedStatus(job, "CANCELLED", 5);
                }
                new MediaDeletionService(mapper, processing, reviews, storage).deleteMedia("default", task, draft.versionId());
                assertThat(client.listObjectsV2(b -> b.bucket(properties.getBucket())).contents()).isEmpty();
                assertThat(mapper.findVersionContext(task, "default", draft.versionId())).isPresent();
                System.out.println("S3_REPAIR_NATIVE_HANDOFF_RECOVERY_OK schemaVersion=" + schemaVersion + " durationMs=" + observedDuration + " files=8 autoSteps=6 generation=2 sourceProbeCalls=1");
                } finally { org.springframework.test.util.ReflectionTestUtils.invokeMethod(ffprobe,"closeOutputReaderExecutor"); }
            }
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="LINKAGENT_GENERATED_NATIVE", matches="true")
    void scheduledWorkerCancellationStopsActualMediaProcesses(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var json = new ObjectMapper(); var source = java.nio.file.Path.of(System.getenv("LINKAGENT_RENDERER_SAMPLE_ROOT"));
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(source.resolve("input.json").toFile());
        var job = queuedAutomaticJob(task(), document);
        var prepared = java.nio.file.Files.createDirectories(root.resolve(job.runId()).resolve(job.sourceAttemptId()));
        for (var asset : document.path("assets")) {
            String name = asset.path("path").asText(); var destination = prepared.resolve(name).normalize();
            assertThat(destination.startsWith(prepared)).isTrue(); java.nio.file.Files.createDirectories(destination.getParent());
            java.nio.file.Files.copy(source.resolve(name), destination);
        }
        var inputFile = prepared.resolve("input.json"); json.writeValue(inputFile.toFile(), document);
        var properties = new com.link.linkagent.creator.creation.render.FixedProjectRendererProperties();
        properties.setEnabled(true); properties.setPythonPath("python"); properties.setWorkRoot(root.toString()); properties.setModuleRoot(System.getenv("LINKAGENT_RENDERER_MODULE_ROOT"));
        properties.setTimeout(Duration.ofSeconds(120));
        var renderer = new com.link.linkagent.creator.creation.render.FixedProjectRenderService(properties, json);
        var files = new FixedProjectJobFiles(properties, json);
        fixedJobs.attach(files.freeze(job.taskId(), "default", new com.link.linkagent.creator.creation.render.FixedProjectRenderService.Request(
                inputFile, job.runId(), job.sourceAttemptId(), document.path("storyboardId").asText(), document.path("inputHash").asText())));
        var storage = mock(ObjectStorageService.class);
        var drafts = new com.link.linkagent.creator.creation.service.GeneratedDraftService(renderer, generated, storage, new ObjectStorageProperties(), json);
        try (var context = scheduledFixedWorker(files, renderer, drafts)) {
            List<ProcessHandle> observed = List.of(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                observed = ProcessHandle.current().descendants().filter(process -> process.info().commandLine().orElse("").contains(job.runId())).toList();
                if (observed.stream().anyMatch(process -> process.info().command().orElse("").contains("ffmpeg"))) break;
                Thread.sleep(25);
            }
            assertThat(observed).anyMatch(process -> process.info().command().orElse("").contains("ffmpeg"));
            runtime.cancel(job.taskId(), "default", job.runId()); awaitFixedStatus(job, "CANCELLED", 12);
            for (var process : observed) if (process.isAlive()) process.onExit().get(5, TimeUnit.SECONDS);
            assertThat(observed).noneMatch(ProcessHandle::isAlive);
            assertThat(generatedMapper.registered(job.taskId(), "default", job.runId())).isEmpty();
            assertThat(mapper.findDraftVideo(job.taskId(), "default")).isEmpty();
            assertThat(automaticMapper.steps(job.taskId(), "default", job.runId())).filteredOn(step -> "COMPLETED".equals(step.stepStatus())).hasSize(1);
            verifyNoInteractions(storage);
            System.out.println("S3D_NATIVE_CANCEL_WITHOUT_FAKE_STEPS_OK stoppedMediaProcesses=" + observed.size());
        }
    }

    private static String nativeSha(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static List<GeneratedDraftFile> generatedFiles() {
        return List.of("video.mp4", "subtitles.srt", "cover.png", "frame-start.png", "frame-middle.png", "frame-end.png", "render.log", "manifest.json")
                .stream().map(name -> new GeneratedDraftFile(name, name.endsWith(".mp4") ? "video/mp4" : name.endsWith(".png") ? "image/png"
                        : name.endsWith(".srt") ? "application/x-subrip" : name.endsWith(".json") ? "application/json" : "text/plain", 1024, "a".repeat(64))).toList();
    }

    private static GeneratedDraftAttempt prepareGenerated(GeneratedDraftLease lease, String attempt) {
        return generated.prepare(lease, attempt, "a".repeat(64), "c".repeat(64), "board", "bucket", generatedFiles());
    }

    private static GeneratedDraftLease fullLease(String task, String input) {
        runtime.initialize(task, "default", "budget-full", revision(task), cny("20"));
        var queued = runtime.startProduction(task, "default", "full", revision(task), input, cny("0"));
        var claimed = runtime.claim(task, "default", queued.runId(), "generated-worker", 600).orElseThrow();
        return new GeneratedDraftLease(task, "default", claimed.runId(), "generated-worker", claimed.leaseGeneration());
    }

    private static String fullInput(String task, String contentHash) {
        String plan = confirmedPlan(task);
        jdbc.update("UPDATE creator_production_plan SET production_method='AI_GENERATION' WHERE plan_id=?", plan);
        jdbc.update("INSERT INTO creator_production_step (step_id,plan_id,task_id,sequence_no,phase,step_name,objective,status) VALUES (?,?,?,1,'SCRIPT','真实产物步骤夹具','仅验证持久化','COMPLETED')", UUID.randomUUID().toString(), plan, task);
        var values = jdbc.queryForMap("SELECT session_id,confirmed_result_id FROM creator_workflow_session WHERE task_id=? ORDER BY id DESC LIMIT 1", task);
        var input = new GeneratedDraftInput(values.get("session_id").toString(), values.get("confirmed_result_id").toString(), plan,
                mapper.findDraftVideo(task, "default").map(DraftVideoRecord::versionId).orElse(null), "board", contentHash);
        try { return new ObjectMapper().writeValueAsString(input); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String confirmedPlan(String task) {
        jdbc.update("UPDATE creator_task SET planning_skipped = 0 WHERE task_id = ?", task);
        String session = UUID.randomUUID().toString(); String result = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO creator_workflow_session (session_id, task_id, stage, status, confirmed_result_id) VALUES (?, ?, 'PRE_PUBLISH', 'CONFIRMED', ?)", session, task, result);
        jdbc.update("INSERT INTO creator_suggestion (suggestion_id, task_id, session_id, content_summary, raw_output) VALUES (?, ?, ?, '确认的方案', '{}')", result, task, session);
        String plan = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO creator_production_plan (plan_id, task_id, owner_id, plan_version, video_category, production_method, target_audience, core_promise, target_duration_ms, available_assets, tool_preferences, status, idempotency_key) VALUES (?, ?, 'default', 1, 'PROJECT_DEMO', 'SCREEN_RECORDING', '开发者', '原来的目标', 90000, '[]', '[]', 'READY', ?)", plan, task, plan);
        jdbc.update("INSERT INTO creator_material (task_id, material_type, content) VALUES (?, 'SCRIPT', '原始素材')", task);
        return plan;
    }

    private static ObjectStorageService mockStorage() {
        var storage = mock(ObjectStorageService.class);
        when(storage.headObject(anyString(), anyString())).thenReturn(new StoredObjectMetadata(1024, "video/mp4", "etag"));
        return storage;
    }

    private static CreationWorkspaceService workspace(ObjectStorageService storage) {
        return new CreationWorkspaceService(mapper, workspaceMapper, selections, storage, new ObjectMapper());
    }

    private static MediaUploadRecord readyVideo(String task, String key) {
        var value = save(task, key);
        complete(value);
        jdbc.update("UPDATE creator_task SET planning_skipped = 1 WHERE task_id = ?", task);
        jdbc.update("UPDATE creator_draft_video SET status = 'READY_FOR_REVIEW' WHERE version_id = ?", value.versionId());
        String reviewId = review(value, processingJob(value, "COMPLETED"), "COMPLETED");
        jdbc.update("INSERT INTO creator_preflight_step (step_id, review_id, step_type, sequence_no, status, input_fingerprint) VALUES (?, ?, 'ANALYZE_VIDEO', 3, 'SUCCEEDED', ?)",
                UUID.randomUUID().toString(), reviewId, "a".repeat(64));
        assertThat(reviews.attachReviewToDraft(task, "default", value.versionId(), reviewId)).isEqualTo(1);
        return value;
    }

    private static BilibiliAccountRecord account() {
        return bilibili.findAccountByUserId("default").orElseThrow();
    }

    private static BilibiliVideoSyncPayload verification(String bvid, String status) {
        return new BilibiliVideoSyncPayload("27058248", null, null, false, false, List.of(),
                List.of(new BilibiliVideoVerificationResult(bvid, status, "27058248", "隔离同步结果")), List.of());
    }

    private static ProductionPlanRecord plan(String task, String planId) {
        return plan(task, planId, 1);
    }

    private static ProductionPlanRecord plan(String task, String planId, int version) {
        return new ProductionPlanRecord(null, planId, task, "default", version,
                "PROJECT_DEMO", "SCREEN_RECORDING", "开发者", "看懂新方向", 90000L,
                "[]", null, "[]", null, null, null, "GENERATING", null, null,
                UUID.randomUUID().toString(), null, null);
    }

    private static String processingJob(MediaUploadRecord value, String status) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO creator_media_processing_job
                    (job_id, version_id, task_id, frame_interval_seconds, target_resolution, target_height,
                     model_plan, pricing_version, estimated_frame_count, estimated_visual_input_tokens,
                     estimated_visual_output_tokens, estimated_asr_seconds, estimated_visual_cost_usd,
                     estimated_asr_cost_usd, estimated_total_cost_usd, status, lease_owner, lease_expires_at)
                VALUES (?, ?, ?, 10, 'P720', 720, 'FLASH', 'isolated-test', 1, 1, 1, 0, 0, 0, 0, ?, ?, ?)
                """, id, value.versionId(), value.taskId(), status,
                "RUNNING".equals(status) ? "test-worker" : null,
                "RUNNING".equals(status) ? LocalDateTime.now().plusMinutes(5) : null);
        return id;
    }

    private static String review(MediaUploadRecord value, String jobId, String status) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO creator_preflight_review
                    (review_id, task_id, version_id, processing_job_id, idempotency_key, status,
                     current_step, input_fingerprint, provider_snapshot, executive_summary, lease_owner, lease_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ANALYZE_VIDEO', ?, '{}', '隔离测试中的原始试映结论', ?, ?)
                """, id, value.taskId(), value.versionId(), jobId, id, status, "a".repeat(64),
                "RUNNING".equals(status) ? "test-worker" : null,
                "RUNNING".equals(status) ? LocalDateTime.now().plusMinutes(5) : null);
        return id;
    }

    private static String state(String table, String column, String id) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE " + column + " = ? AND is_deleted = 0", String.class, id);
    }

    private static CreatorMediaWorkflowGateService gate() {
        var properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        return new CreatorMediaWorkflowGateService(properties, mapper,
                mock(MediaProcessingMapper.class), mock(PreflightReviewMapper.class), mock(CreatorWorkflowMapper.class),
                mock(CreatorSuggestionMapper.class), mock(DraftVideoProbeRecoveryService.class));
    }

    private static String task() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO creator_task (task_id, user_id, task_name, video_type, status, planning_skipped) VALUES (?, 'default', '隔离版本测试', '知识分享', 'DRAFT', 1)", id);
        return id;
    }

    private static MediaUploadRecord save(String task, String key) {
        var upload = upload(task, UUID.randomUUID().toString(), key);
        return persistence.saveCreatedUpload(draft(upload, 0), upload, revision(task));
    }

    private static long revision(String task) {
        return mapper.findMediaWorkspace(task, "default").map(value -> value.workspaceRevision()).orElse(-1L);
    }

    private static MediaUploadRecord upload(String task, String session, String key) {
        String version = UUID.randomUUID().toString();
        return new MediaUploadRecord(null, session, version, task, "default", "storage-" + session,
                "tests/" + version, "video/mp4", 1024L, "a".repeat(64), 1024, 1, "CREATED", key,
                null, LocalDateTime.now().plusHours(1), null, null, null);
    }

    private static DraftVideoRecord draft(MediaUploadRecord value, int number) {
        return new DraftVideoRecord(null, value.versionId(), value.taskId(), value.ownerId(), number,
                "测试版本", "test.mp4", "isolated-test", value.objectKey(), "video/mp4", 1024L,
                null, null, null, null, null, null, null, null, "UPLOADING", null, null, null);
    }

    private static void complete(MediaUploadRecord value) {
        assertThat(mapper.markUploadUploading(value.taskId(), value.ownerId(), value.uploadSessionId())).isEqualTo(1);
        assertThat(mapper.markUploadVerifying(value.taskId(), value.ownerId(), value.uploadSessionId())).isEqualTo(1);
        persistence.markUploadCompleted(value, "video/mp4", 1024L, LocalDateTime.now());
    }

    private static int count(String table, String task) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id = ?", Integer.class, task);
    }
}
