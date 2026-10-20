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
import com.link.linkagent.creator.creation.service.FixedProjectJobPersistenceService;
import com.link.linkagent.creator.creation.service.FixedProjectJobFiles;
import com.link.linkagent.creator.creation.service.FixedProjectWorker;
import com.link.linkagent.creator.creation.service.CreationWorkerProperties;
import com.link.linkagent.creator.creation.service.GeneratedDraftPersistenceService;
import com.link.linkagent.creator.creation.model.*;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
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
    static CreationRuntimeService runtime;
    static GeneratedDraftPersistenceService generated;
    static GeneratedDraftMapper generatedMapper;
    static FixedProjectJobMapper fixedJobMapper;
    static FixedProjectJobPersistenceService fixedJobs;
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
        var runtimeProxy = new ProxyFactory(new CreationRuntimeService(mapper, runtimeMapper, workspaceMapper, new ObjectMapper()));
        runtimeProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        runtime = (CreationRuntimeService) runtimeProxy.getProxy();
        generatedMapper = session.getMapper(GeneratedDraftMapper.class);
        var generatedProxy = new ProxyFactory(new GeneratedDraftPersistenceService(mapper, runtimeMapper, generatedMapper, new ObjectMapper()));
        generatedProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        generated = (GeneratedDraftPersistenceService) generatedProxy.getProxy();
        fixedJobMapper = session.getMapper(FixedProjectJobMapper.class);
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

    private FixedProjectWorker fixedWorker(FixedProjectJobFiles files,
            com.link.linkagent.creator.creation.render.FixedProjectRenderService renderer,
            com.link.linkagent.creator.creation.service.GeneratedDraftService drafts) {
        var properties = new CreationWorkerProperties(); properties.setEnabled(true); properties.setLeaseSeconds(5); properties.setHeartbeatSeconds(1);
        return new FixedProjectWorker(properties, fixedJobMapper, runtime, files, renderer, drafts, generated);
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @org.springframework.scheduling.annotation.EnableScheduling
    static class FixedWorkerScheduling { }

    private org.springframework.context.annotation.AnnotationConfigApplicationContext scheduledFixedWorker(FixedProjectJobFiles files,
            com.link.linkagent.creator.creation.render.FixedProjectRenderService renderer,
            com.link.linkagent.creator.creation.service.GeneratedDraftService drafts) {
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

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="LINKAGENT_GENERATED_NATIVE", matches="true")
    void realRenderedVideoSurvivesPrivateStorageRegistrationProbeAndDeletion(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var json = new ObjectMapper();
        java.nio.file.Path source = java.nio.file.Path.of(System.getenv("LINKAGENT_RENDERER_SAMPLE_ROOT"));
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(source.resolve("input.json").toFile());
        String task = task(); var job = queuedFixedJob(task, document);
        String attemptId = job.sourceAttemptId();
        java.nio.file.Path attempt = java.nio.file.Files.createDirectories(root.resolve(job.runId()).resolve(attemptId));
        for (var asset : document.path("assets")) {
            String name = asset.path("path").asText(); java.nio.file.Path target = attempt.resolve(name).normalize();
            assertThat(target.startsWith(attempt)).isTrue();
            java.nio.file.Files.createDirectories(target.getParent()); java.nio.file.Files.copy(source.resolve(name), target);
        }
        String inputHash = document.path("inputHash").asText();
        java.nio.file.Path inputFile = attempt.resolve("input.json"); json.writeValue(inputFile.toFile(), document);
        var rendererProperties = new com.link.linkagent.creator.creation.render.FixedProjectRendererProperties();
        rendererProperties.setEnabled(true); rendererProperties.setPythonPath("python"); rendererProperties.setModuleRoot("/opt/renderer");
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
                var storage = new com.link.linkagent.creator.media.storage.S3ObjectStorageService(client, presigner, presigner, properties);
                var service = new com.link.linkagent.creator.creation.service.GeneratedDraftService(renderer, generated, storage, properties, json);
                try (var context = scheduledFixedWorker(jobFiles, renderer, service)) {
                    awaitFixedStatus(job, "WAITING_DEPENDENCY", 150);
                    assertThat(context.getBean(FixedProjectWorker.class)).isNotNull();
                }
                var registered = generatedMapper.registered(task, "default", job.runId()).orElseThrow();
                var draft = mapper.findDraftVideoByVersion(task, "default", registered.versionId()).orElseThrow();
                assertThat(draft.status()).isEqualTo("UPLOADED");
                assertThat(client.listObjectsV2(b -> b.bucket(properties.getBucket())).contents()).hasSize(8);
                var waiting = runtimeMapper.run(task, "default", job.runId()).orElseThrow();
                assertThat(waiting.leaseGeneration()).isEqualTo(1); assertThat(waiting.completedAt()).isNull();
                assertThat(waiting.dependencyId()).isEqualTo(draft.versionId());
                // 移走本测试创建的完整工作目录，接管仍须从登记事实恢复同一版本。
                java.nio.file.Files.move(root.resolve(job.runId()), root.resolve("removed-workdir"));
                runtime.wake(task, "default", job.runId(), waiting.runRevision());
                try (var worker = fixedWorker(jobFiles, renderer, service)) { worker.poll(); awaitFixedStatus(job, "WAITING_DEPENDENCY", 5); }
                assertThat(runtimeMapper.run(task, "default", job.runId()).orElseThrow().leaseGeneration()).isEqualTo(2);
                assertThat(mapper.findMediaWorkspace(task, "default").orElseThrow().nextVersionNo()).isEqualTo(2);
                assertThat(client.listObjectsV2(b -> b.bucket(properties.getBucket())).contents()).hasSize(8);
                assertThat(runtime.snapshot(task, "default").executionAvailable()).isFalse();
                var mediaProperties = new CreatorMediaProperties(); mediaProperties.setEnabled(true);
                var ffprobe = new com.link.linkagent.creator.media.probe.service.FfprobeMediaProbeService(mediaProperties, json);
                long observedDuration = 0;
                try {
                    var probe = new com.link.linkagent.creator.media.probe.service.DraftVideoProbeService(mediaProperties, storage, ffprobe, mapper,
                            new DraftVideoProbeRecoveryService(mediaProperties, mapper));
                    var probed = probe.probeDraftVideo("default", task, draft.versionId());
                    observedDuration = probed.durationMs();
                    assertThat(probed.durationMs()).isBetween(84100L, 84240L);
                    assertThat(probed.status()).isEqualTo("READY_FOR_REVIEW");
                    assertThat(readyGate.inspectPostPublishReadiness(task, "default", "发布").ready()).isFalse();
                } finally { org.springframework.test.util.ReflectionTestUtils.invokeMethod(ffprobe, "closeOutputReaderExecutor"); }
                runtime.cancel(task, "default", job.runId());
                try (var worker = fixedWorker(jobFiles, renderer, service)) { worker.poll(); awaitFixedStatus(job, "CANCELLED", 5); }
                new MediaDeletionService(mapper, processing, reviews, storage).deleteMedia("default", task, draft.versionId());
                assertThat(client.listObjectsV2(b -> b.bucket(properties.getBucket())).contents()).isEmpty();
                assertThat(mapper.findVersionContext(task, "default", draft.versionId())).isPresent();
                System.out.println("S3C_NATIVE_WORKER_RESTART_PROBE_DELETE_OK durationMs=" + observedDuration + " files=8 generation=2");
            }
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="LINKAGENT_GENERATED_NATIVE", matches="true")
    void scheduledWorkerCancellationStopsActualMediaProcesses(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var json = new ObjectMapper(); var source = java.nio.file.Path.of(System.getenv("LINKAGENT_RENDERER_SAMPLE_ROOT"));
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(source.resolve("input.json").toFile());
        var job = queuedFixedJob(task(), document);
        var prepared = java.nio.file.Files.createDirectories(root.resolve(job.runId()).resolve(job.sourceAttemptId()));
        for (var asset : document.path("assets")) {
            String name = asset.path("path").asText(); var destination = prepared.resolve(name).normalize();
            assertThat(destination.startsWith(prepared)).isTrue(); java.nio.file.Files.createDirectories(destination.getParent());
            java.nio.file.Files.copy(source.resolve(name), destination);
        }
        var inputFile = prepared.resolve("input.json"); json.writeValue(inputFile.toFile(), document);
        var properties = new com.link.linkagent.creator.creation.render.FixedProjectRendererProperties();
        properties.setEnabled(true); properties.setPythonPath("python"); properties.setWorkRoot(root.toString()); properties.setModuleRoot("/opt/renderer");
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
            verifyNoInteractions(storage);
            System.out.println("S3C_NATIVE_SCHEDULED_CANCEL_OK stoppedMediaProcesses=" + observed.size());
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
