package com.link.linkagent.creator.media.preflight;

import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.probe.service.DraftVideoProbeRecoveryService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.service.CreatorTaskService;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/** 只验证真实 MySQL 任务锁、传统作业和费用流水；不执行媒体处理、存储或供应商请求。 */
@Testcontainers
class CreatorMediaDeletionAccountingIntegrationTest {
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "media-deletion-accounting-test")
            .withEnv("MYSQL_ROOT_HOST", "%").withEnv("MYSQL_DATABASE", "link_agent")
            .withLabels(Map.of("linkagent.scope", "media-deletion-accounting-test"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("sql/init.sql"), "/docker-entrypoint-initdb.d/01-init.sql")
            .withExposedPorts(3306)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    static JdbcTemplate jdbc;
    static CreatorTaskMapper tasks;
    static MediaUploadMapper media;
    static PreflightReviewMapper reviews;
    static MediaProcessingMapper processing;
    static CreatorTaskService taskService;
    static DraftVideoProbeRecoveryService probes;
    static TransactionTemplate transactions;

    @BeforeAll
    static void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/link_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "root", "media-deletion-accounting-test");
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(CreatorTaskMapper.class, MediaUploadMapper.class, PreflightReviewMapper.class,
                MediaProcessingMapper.class)) {
            configuration.addMapper(mapper);
        }
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration); factory.afterPropertiesSet();
        var session = new SqlSessionTemplate(factory.getObject());
        tasks = session.getMapper(CreatorTaskMapper.class);
        media = session.getMapper(MediaUploadMapper.class);
        reviews = session.getMapper(PreflightReviewMapper.class);
        processing = session.getMapper(MediaProcessingMapper.class);
        taskService = new CreatorTaskService(tasks);
        probes = new DraftVideoProbeRecoveryService(new CreatorMediaProperties(), media);
    }

    @Test
    void workersExcludeHistoricallyDeletedTasksAndWrongOwnersWhenFindingAndClaimingQueuedWork() {
        String task = task();
        Operation job = operation(task, "processing", "QUEUED", "default");
        Operation review = operation(task, "review", "QUEUED", "default");
        // 模拟修复前遗留的孤儿作业；放到最早，防止漏过滤时被其他测试数据掩盖。
        jdbc.update("UPDATE creator_media_processing_job SET create_time='2000-01-01' WHERE job_id=?", job.id);
        jdbc.update("UPDATE creator_preflight_review SET create_time='2000-01-01' WHERE review_id=?", review.id);
        jdbc.update("UPDATE creator_task SET is_deleted=1 WHERE task_id=?", task);
        assertThat(processing.findNextQueuedJob().map(row -> task.equals(row.taskId())).orElse(false)).isFalse();
        assertThat(reviews.findNextRunnableReview().map(row -> task.equals(row.taskId())).orElse(false)).isFalse();
        LocalDateTime lease = LocalDateTime.now().plusMinutes(1);
        assertThat(processing.claimJob(job.id, "test-worker", lease, 3)).isZero();
        assertThat(reviews.claimReview(review.id, "test-worker", lease)).isZero();

        String live = task();
        Operation foreignJob = operation(live, "processing", "QUEUED", "another-owner");
        Operation foreignReview = operation(live, "review", "QUEUED", "another-owner");
        assertThat(processing.findNextQueuedJob().map(row -> live.equals(row.taskId())).orElse(false)).isFalse();
        assertThat(reviews.findNextRunnableReview().map(row -> live.equals(row.taskId())).orElse(false)).isFalse();
        assertThat(processing.claimJob(foreignJob.id, "test-worker", lease, 3)).isZero();
        assertThat(reviews.claimReview(foreignReview.id, "test-worker", lease)).isZero();

        Operation liveJob = operation(live, "processing", "QUEUED", "default");
        Operation liveReview = operation(live, "review", "QUEUED", "default");
        assertThat(processing.findNextQueuedJob().map(row -> row.jobId())).contains(liveJob.id);
        assertThat(reviews.findNextRunnableReview().map(row -> row.reviewId())).contains(liveReview.id);
        assertThat(processing.claimJob(liveJob.id, "test-worker", lease, 3)).isEqualTo(1);
        assertThat(reviews.claimReview(liveReview.id, "test-worker", lease)).isEqualTo(1);
        jdbc.update("UPDATE creator_media_processing_job SET status='COMPLETED' WHERE job_id=?", liveJob.id);
        jdbc.update("UPDATE creator_preflight_review SET status='COMPLETED' WHERE review_id=?", liveReview.id);
        delete(live);
    }

    @ParameterizedTest
    @CsvSource({"upload,CREATED", "upload,UPLOADING", "upload,VERIFYING", "draft,PROBING",
            "processing,QUEUED", "processing,RUNNING", "review,QUEUED", "review,RUNNING",
            "review,RETRY_WAIT", "review,CANCEL_REQUESTED"})
    void activeLegacyOperationsBlockDeletionAndTheirTerminalStateAllowsIt(String kind, String status) {
        String task = task();
        Operation operation = operation(task, kind, status, "default");
        assertThat(tasks.countDeletionBlockers(task, "default")).isEqualTo(1);
        assertThatThrownBy(() -> delete(task)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(tasks.findTaskByTaskId(task)).isPresent();
        jdbc.update("UPDATE " + operation.table + " SET status=? WHERE " + operation.idColumn + "=?",
                "draft".equals(kind) ? "READY_FOR_REVIEW" : "COMPLETED", operation.id);
        assertThat(tasks.countDeletionBlockers(task, "default")).isZero();
        delete(task);
        assertThat(tasks.findTaskByTaskId(task)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"upload,UPLOADING", "draft,PROBING", "processing,RUNNING", "review,RUNNING"})
    void foreignOwnersAndDeletedLegacyRowsDoNotBlockCurrentTask(String kind, String status) {
        String task = task();
        operation(task, kind, status, "another-owner");
        Operation deleted = operation(task, kind, status, "default");
        jdbc.update("UPDATE " + deleted.table + " SET is_deleted=1 WHERE " + deleted.idColumn + "=?", deleted.id);
        assertThat(tasks.countDeletionBlockers(task, "default")).isZero();
        delete(task);
        assertThat(tasks.findTaskByTaskId(task)).isEmpty();
    }

    @Test
    void probeClaimCommittedFirstMakesConcurrentDeletionWaitAndThenReject() throws Exception {
        String task = task();
        String version = operation(task, "draft", "UPLOADED", "default").id;
        CountDownLatch claimed = new CountDownLatch(1), release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var probe = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                probes.claim(task, "default", version, "test-probe");
                claimed.countDown(); await(release);
            }));
            assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
            var deletion = executor.submit(() -> rejectionStatus(() -> delete(task)));
            assertThatThrownBy(() -> deletion.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); probe.get(10, TimeUnit.SECONDS);
            assertThat(deletion.get(10, TimeUnit.SECONDS)).isEqualTo(HttpStatus.CONFLICT);
            assertThat(tasks.findTaskByTaskId(task)).isPresent();
            assertThat(media.findDraftVideoByVersion(task, "default", version).orElseThrow().status()).isEqualTo("PROBING");
        } finally {
            release.countDown(); executor.shutdownNow(); executor.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void deletionCommittedFirstMakesWaitingProbeRejectWithoutWritingProbingState() throws Exception {
        String task = task();
        String version = operation(task, "draft", "UPLOADED", "default").id;
        CountDownLatch deleted = new CountDownLatch(1), release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var deletion = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                taskService.deleteTask(task); deleted.countDown(); await(release);
            }));
            assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();
            var probe = executor.submit(() -> rejectionStatus(() -> transactions.executeWithoutResult(tx ->
                    probes.claim(task, "default", version, "test-probe"))));
            assertThatThrownBy(() -> probe.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); deletion.get(10, TimeUnit.SECONDS);
            assertThat(probe.get(10, TimeUnit.SECONDS)).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(tasks.findTaskByTaskId(task)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT status FROM creator_draft_video WHERE version_id=?", String.class, version))
                    .isEqualTo("UPLOADED");
        } finally {
            release.countDown(); executor.shutdownNow(); executor.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void aggregateIncludesKnownFailedCallsAndKeepsUnknownAmountsNullUntilResolved() {
        String task = task();
        String review = operation(task, "review", "FAILED", "default").id;
        assertThat(reviews.sumActualCost(review)).isEqualByComparingTo(BigDecimal.ZERO);
        String success = id(), failed = id(), unknown = id();
        reviews.insertVideoCall(success, task, "version", review, "video-step", "test-model", "fingerprint", BigDecimal.ONE);
        reviews.recordCallUsage(success, 40L, 120L, new BigDecimal("0.10"));
        reviews.completeVideoCall(success, 40L, 120L, new BigDecimal("0.10"), 1);
        reviews.insertVideoCall(failed, task, "version", review, "segment-step", "test-model", "fingerprint", BigDecimal.ONE);
        reviews.recordCallUsage(failed, 20L, 60L, new BigDecimal("0.20"));
        reviews.failVideoCall(failed, "INVALID_JSON", "已返回用量但正文解析失败");
        assertThat(jdbc.queryForObject("SELECT input_tokens FROM creator_media_api_call_log WHERE call_id=?", Long.class, failed)).isEqualTo(20L);
        assertThat(jdbc.queryForObject("SELECT output_tokens FROM creator_media_api_call_log WHERE call_id=?", Long.class, failed)).isEqualTo(60L);
        assertThat(reviews.sumActualCost(review)).isEqualByComparingTo("0.30");
        reviews.refreshActualCost(review);
        assertThat(cost(review)).isEqualByComparingTo("0.30");
        reviews.insertTextScreeningCall(unknown, task, "version", review, "audience-step", "fingerprint");
        reviews.failTextScreeningCall(unknown, "RESPONSE_LOST", "已发送但未确认用量");
        assertThat(reviews.sumActualCost(review)).isNull();
        reviews.refreshActualCost(review);
        assertThat(cost(review)).isNull();
        reviews.recordCallUsage(unknown, 10L, 30L, new BigDecimal("0.05"));
        reviews.completeTextScreeningCall(unknown, 10, 30, 3);
        assertThat(reviews.sumActualCost(review)).isEqualByComparingTo("0.35");
        reviews.refreshActualCost(review);
        assertThat(cost(review)).isEqualByComparingTo("0.35");
        jdbc.update("UPDATE creator_media_api_call_log SET is_deleted=1 WHERE call_id=?", failed);
        assertThat(reviews.sumActualCost(review)).isEqualByComparingTo("0.15");
    }

    private BigDecimal cost(String review) {
        return jdbc.queryForObject("SELECT actual_cost_usd FROM creator_preflight_review WHERE review_id=?", BigDecimal.class, review);
    }

    private void delete(String task) { transactions.executeWithoutResult(tx -> taskService.deleteTask(task)); }
    private static HttpStatus rejectionStatus(Runnable action) {
        try { action.run(); return HttpStatus.OK; }
        catch (ResponseStatusException error) { return HttpStatus.valueOf(error.getStatusCode().value()); }
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    private String task() {
        CreatorTaskRecord task = new CreatorTaskRecord();
        task.setTaskId(id()); task.setUserId("default"); task.setTaskName("媒体删除与账目回归");
        task.setVideoType("知识"); task.setStatus("DRAFT"); tasks.insertTask(task); return task.getTaskId();
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private record Operation(String table, String idColumn, String id) { }

    private Operation operation(String task, String kind, String status, String owner) {
        String id = id();
        switch (kind) {
            case "upload" -> {
                jdbc.update("""
                        INSERT INTO creator_media_upload(upload_session_id,version_id,task_id,owner_id,storage_upload_id,
                        object_key,content_type,expected_size,file_fingerprint,part_size,total_parts,status,idempotency_key,expires_at)
                        VALUES(?,?,?,?,'test-upload','test-object','video/mp4',100,'test-fingerprint',100,1,?,?,DATE_ADD(NOW(),INTERVAL 1 HOUR))
                        """, id, "version", task, owner, status, id);
                return new Operation("creator_media_upload", "upload_session_id", id);
            }
            case "draft" -> {
                Integer number = jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM creator_draft_video WHERE task_id=?", Integer.class, task);
                jdbc.update("""
                        INSERT INTO creator_draft_video(version_id,task_id,owner_id,version_no,version_name,original_file_name,
                        bucket_name,object_key,content_type,file_size,status)
                        VALUES(?,?,?,?, '测试版本','test.mp4','private-test','test-object','video/mp4',100,?)
                        """, id, task, owner, number, status);
                return new Operation("creator_draft_video", "version_id", id);
            }
            case "processing" -> {
                jdbc.update("""
                        INSERT INTO creator_media_processing_job(job_id,version_id,task_id,owner_id,frame_interval_seconds,
                        target_resolution,target_height,model_plan,pricing_version,estimated_frame_count,estimated_visual_input_tokens,
                        estimated_visual_output_tokens,estimated_asr_seconds,estimated_visual_cost_usd,estimated_asr_cost_usd,estimated_total_cost_usd,status)
                        VALUES(?,'version',?,?,10,'P720',720,'FLASH','test-prices',0,0,0,0,0,0,0,?)
                        """, id, task, owner, status);
                return new Operation("creator_media_processing_job", "job_id", id);
            }
            case "review" -> {
                jdbc.update("""
                        INSERT INTO creator_preflight_review(review_id,task_id,version_id,owner_id,processing_job_id,idempotency_key,
                        status,input_fingerprint,provider_snapshot) VALUES(?,?,'version',?,'processing-job',?,?,'fingerprint','{}')
                        """, id, task, owner, id, status);
                return new Operation("creator_preflight_review", "review_id", id);
            }
            default -> throw new IllegalArgumentException(kind);
        }
    }
}
