package com.link.linkagent.creator.creation.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.asset.mapper.CreationAssetMapper;
import com.link.linkagent.creator.creation.asset.model.AssetRequests.Create;
import com.link.linkagent.creator.creation.asset.model.AssetRequests.Part;
import com.link.linkagent.creator.creation.asset.model.AssetRequests.Snapshot;
import com.link.linkagent.creator.creation.asset.service.CreationAssetPersistence;
import com.link.linkagent.creator.creation.asset.service.CreationAssetService;
import com.link.linkagent.creator.media.config.ObjectStorageProperties;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.storage.PresignedObjectRead;
import com.link.linkagent.creator.media.storage.PresignedUploadPart;
import com.link.linkagent.creator.media.storage.StoredObjectMetadata;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.link.linkagent.creator.creation.asset.service.CreationAssetPersistence.PART_BYTES;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 MySQL 和事务代理验证素材事实；存储签名及 HEAD 是替身，不代表云端已上传或已解码。 */
@Testcontainers
class CreationAssetPersistenceIntegrationTest {
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "asset-isolated-test")
            .withEnv("MYSQL_ROOT_HOST", "%")
            .withEnv("MYSQL_DATABASE", "link_agent")
            .withLabels(Map.of("linkagent.scope", "creation-s4-asset-test"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("sql/init.sql"),
                    "/docker-entrypoint-initdb.d/01-init.sql")
            .withExposedPorts(3306)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    static JdbcTemplate jdbc;
    static CreationAssetMapper mapper;
    static CreationAssetPersistence persistence;
    static CreatorTaskMapper tasks;
    ObjectStorageProperties properties;
    ObjectStorageService storage;
    CreationAssetService service;

    @BeforeAll
    static void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:mysql://" + MYSQL.getHost() + ":"
                + MYSQL.getMappedPort(3306) + "/link_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "root", "asset-isolated-test");
        jdbc = new JdbcTemplate(source);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(CreationAssetMapper.class);
        configuration.addMapper(CreatorTaskMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.afterPropertiesSet();
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(CreationAssetMapper.class);
        tasks = new SqlSessionTemplate(factory.getObject()).getMapper(CreatorTaskMapper.class);
        var manager = new DataSourceTransactionManager(source);
        var proxy = new ProxyFactory(new CreationAssetPersistence(mapper));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        persistence = (CreationAssetPersistence) proxy.getProxy();
    }

    @BeforeEach
    void storageBoundary() {
        properties = new ObjectStorageProperties();
        properties.setBucket("isolated-assets");
        properties.setAccessKey("not-a-real-access-key");
        properties.setSecretKey("not-a-real-secret-key");
        storage = mock(ObjectStorageService.class);
        service = new CreationAssetService(mapper, persistence, properties, storage, new ObjectMapper());
    }

    @Test
    void sameKeyAndDeclarationReturnSameAssetEvenUnderConcurrentAdmission() throws Exception {
        String task = task("default");
        var request = request(PART_BYTES + 31, "a");
        String key = UUID.randomUUID().toString();
        try (var executor = Executors.newFixedThreadPool(3)) {
            var futures = executor.<Snapshot>invokeAll(List.of(
                    () -> service.create(task, "default", key, request),
                    () -> service.create(task, "default", key, request),
                    () -> service.create(task, "default", key, request)));
            String first = futures.getFirst().get(10, TimeUnit.SECONDS).assetId();
            for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS).assetId()).isEqualTo(first);
            assertThat(count("creator_creation_asset", task)).isEqualTo(1);
            assertThat(service.get(task, "default", first).status()).isEqualTo("CREATING");
        }
        verifyNoInteractions(storage);
    }

    @Test
    void sameIdempotencyKeyWithDifferentHashIsRejectedWithoutOverwritingOriginal() {
        String task = task("default");
        var first = service.create(task, "default", "same-key", request(123, "a"));
        conflict(() -> service.create(task, "default", "same-key", request(123, "b")));
        assertThat(service.get(task, "default", first.assetId()).sha256()).isEqualTo("a".repeat(64));
        assertThat(count("creator_creation_asset", task)).isEqualTo(1);
        verifyNoInteractions(storage);
    }

    @Test
    void clientMimeDoesNotChangeCanonicalContentTypeOrBreakSameFileReplay() {
        String task = task("default");
        var first = service.create(task, "default", "mime-key", request(123, "a"));
        var replay = service.create(task, "default", "mime-key",
                new Create("VIDEO_CLIP", "same-name.mp4", "application/octet-stream", 123, "A".repeat(64)));
        assertThat(replay.assetId()).isEqualTo(first.assetId());
        assertThat(mapper.find(task, "default", first.assetId()).orElseThrow().getContentType()).isEqualTo("video/mp4");
        assertThat(replay.sha256()).isEqualTo("a".repeat(64));
        verifyNoInteractions(storage);
    }

    @Test
    void sameFileNameWithNewKeysCreatesIndependentAssetsAndLeavesVideoWorkspaceUntouched() {
        String task = task("default");
        // 指针是受控历史夹具，只验证上传素材不会改写完整成片工作区。
        jdbc.update("INSERT INTO creator_creation_workspace(task_id,owner_id,current_video_version_id,next_version_no,workspace_revision) VALUES (?,'default','existing-video',8,9)", task);
        var first = service.create(task, "default", "first-key", request(123, "a"));
        var second = service.create(task, "default", "second-key", request(123, "a"));
        assertThat(first.assetId()).isNotEqualTo(second.assetId());
        assertThat(mapper.find(task, "default", first.assetId()).orElseThrow().getSourceKey())
                .isNotEqualTo(mapper.find(task, "default", second.assetId()).orElseThrow().getSourceKey());
        assertThat(service.list(task, "default", 0, 20)).hasSize(2);
        assertThat(count("creator_draft_video", task)).isZero();
        assertThat(count("creator_media_upload", task)).isZero();
        var workspace = jdbc.queryForMap("SELECT current_video_version_id,next_version_no,workspace_revision FROM creator_creation_workspace WHERE task_id=?", task);
        assertThat(workspace.get("current_video_version_id")).isEqualTo("existing-video");
        assertThat(((Number) workspace.get("next_version_no")).longValue()).isEqualTo(8);
        assertThat(((Number) workspace.get("workspace_revision")).longValue()).isEqualTo(9);
        verifyNoInteractions(storage);
    }

    @Test
    void realRecordMappingPreservesOrderedPartsAndRepeatedRegistrationCanRefreshEtag() {
        String task = task("default");
        var asset = uploading(task, PART_BYTES + 31);
        persistence.register(task, "default", asset.assetId(), List.of(new Part(2, "second", 31), new Part(1, "first", PART_BYTES)));
        assertThat(mapper.parts(asset.assetId())).containsExactly(new Part(1, "first", PART_BYTES), new Part(2, "second", 31));
        persistence.register(task, "default", asset.assetId(), List.of(new Part(1, "new-etag", PART_BYTES)));
        assertThat(service.get(task, "default", asset.assetId()).parts())
                .containsExactly(new Part(1, "new-etag", PART_BYTES), new Part(2, "second", 31));
        assertThat(service.complete(task, "default", asset.assetId()).status()).isEqualTo("COMPLETING");
        assertThat(service.complete(task, "default", asset.assetId()).status()).isEqualTo("COMPLETING");
        assertThat(mapper.parts(asset.assetId())).hasSize(2);
        verifyNoInteractions(storage);
    }

    static Stream<Part> invalidParts() {
        return Stream.of(new Part(0, "bad", PART_BYTES), new Part(3, "bad", 31),
                new Part(1, "bad", PART_BYTES - 1), new Part(2, "bad", 32),
                new Part(2, "bad", 0), new Part(2, " ", 31),
                new Part(2, "etag\nprivate", 31), new Part(2, "x".repeat(256), 31));
    }

    @ParameterizedTest
    @MethodSource("invalidParts")
    void invalidPartRollsBackEarlierWritesInSameBatch(Part invalid) {
        String task = task("default");
        var asset = uploading(task, PART_BYTES + 31);
        persistence.register(task, "default", asset.assetId(), List.of(new Part(1, "original-etag", PART_BYTES)));
        conflict(() -> persistence.register(task, "default", asset.assetId(),
                List.of(new Part(1, "must-rollback", PART_BYTES), invalid)));
        assertThat(mapper.parts(asset.assetId())).containsExactly(new Part(1, "original-etag", PART_BYTES));
        assertThat(service.get(task, "default", asset.assetId()).status()).isEqualTo("UPLOADING");
    }

    @Test
    void duplicatePartWithinBatchRollsBackAndMissingPartsCannotComplete() {
        String task = task("default");
        var asset = uploading(task, PART_BYTES + 31);
        conflict(() -> persistence.register(task, "default", asset.assetId(),
                List.of(new Part(1, "first", PART_BYTES), new Part(1, "second", PART_BYTES))));
        assertThat(mapper.parts(asset.assetId())).isEmpty();
        persistence.register(task, "default", asset.assetId(), List.of(new Part(1, "first", PART_BYTES)));
        conflict(() -> service.complete(task, "default", asset.assetId()));
        assertThat(service.get(task, "default", asset.assetId()).status()).isEqualTo("UPLOADING");
    }

    @Test
    void liveLeaseCannotBeStolenExpiredLeaseCanBeTakenAndOldTokenCannotPublishResults() {
        String task = task("default");
        var asset = service.create(task, "default", "lease-key", request(123, "a"));
        assertThat(mapper.claim(asset.assetId(), "CREATING", "CREATING", "old-token")).isEqualTo(1);
        assertThat(mapper.heartbeat(asset.assetId(), "old-token")).isEqualTo(1);
        assertThat(mapper.claim(asset.assetId(), "CREATING", "CREATING", "other-token")).isZero();
        assertThat(mapper.candidates()).noneMatch(a -> a.getAssetId().equals(asset.assetId()));
        jdbc.update("UPDATE creator_creation_asset SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE asset_id=?", asset.assetId());
        assertThat(mapper.finish(asset.assetId(), "old-token", "UPLOADING", "old-upload", null, null, null)).isZero();
        assertThat(mapper.candidates()).anyMatch(a -> a.getAssetId().equals(asset.assetId()));
        assertThat(mapper.claim(asset.assetId(), "CREATING", "CREATING", "new-token")).isEqualTo(1);
        assertThat(mapper.heartbeat(asset.assetId(), "old-token")).isZero();
        assertThat(mapper.finish(asset.assetId(), "old-token", "UPLOADING", "old-upload", null, null, null)).isZero();
        assertThat(mapper.finish(asset.assetId(), "new-token", "UPLOADING", "current-upload", null, null, null)).isEqualTo(1);
        var stored = mapper.find(task, "default", asset.assetId()).orElseThrow();
        assertThat(stored.getStorageUploadId()).isEqualTo("current-upload");
        assertThat(stored.getLeaseToken()).isNull();
    }

    @Test
    void failureRequiresExplicitRetryAndPreparingFailureResumesFromUploaded() {
        String task = task("default");
        var asset = service.create(task, "default", "retry-key", request(123, "a"));
        assertThat(mapper.claim(asset.assetId(), "CREATING", "CREATING", "first-token")).isEqualTo(1);
        assertThat(mapper.fail(asset.assetId(), "wrong-token", "WRONG")).isZero();
        assertThat(mapper.fail(asset.assetId(), "first-token", "STORAGE_UNAVAILABLE")).isEqualTo(1);
        var failed = service.get(task, "default", asset.assetId());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failedStage()).isEqualTo("CREATING");
        assertThat(mapper.candidates()).noneMatch(a -> a.getAssetId().equals(asset.assetId()));
        assertThat(service.retry(task, "default", asset.assetId()).status()).isEqualTo("CREATING");
        conflict(() -> service.retry(task, "default", asset.assetId()));
        jdbc.update("UPDATE creator_creation_asset SET status='UPLOADED',storage_upload_id='retry-upload' WHERE asset_id=?", asset.assetId());
        assertThat(mapper.claim(asset.assetId(), "UPLOADED", "PREPARING", "prepare-token")).isEqualTo(1);
        assertThat(mapper.fail(asset.assetId(), "prepare-token", "INVALID_MEDIA")).isEqualTo(1);
        assertThat(service.retry(task, "default", asset.assetId()).status()).isEqualTo("UPLOADED");
        assertThat(service.get(task, "default", asset.assetId()).failureCode()).isNull();
    }

    @Test
    void cancellationInvalidatesLiveTokenAndRemainsCleanupCandidateUntilAcknowledged() {
        String task = task("default");
        var asset = service.create(task, "default", "cancel-key", request(123, "a"));
        assertThat(mapper.claim(asset.assetId(), "CREATING", "CREATING", "cancel-token")).isEqualTo(1);
        var cancelled = service.cancel(task, "default", asset.assetId());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cleanupPending()).isTrue();
        assertThat(mapper.heartbeat(asset.assetId(), "cancel-token")).isZero();
        assertThat(mapper.finish(asset.assetId(), "cancel-token", "UPLOADING", "late-upload", null, null, null)).isZero();
        assertThat(mapper.finish(asset.assetId(), "cancel-token", "READY", null, "a".repeat(64), "{}", "[]")).isZero();
        assertThat(mapper.fail(asset.assetId(), "cancel-token", "LATE_FAILURE")).isZero();
        assertThat(service.cancel(task, "default", asset.assetId()).cleanupPending()).isTrue();
        conflict(() -> service.retry(task, "default", asset.assetId()));
        assertThat(mapper.cleaned(asset.assetId())).isZero();
        assertThat(service.get(task, "default", asset.assetId()).cleanupPending()).isTrue();
        // 用数据库时间推进等待屏障，避免测试真实等待可能尚在途的写入超时。
        jdbc.update("UPDATE creator_creation_asset SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE asset_id=?", asset.assetId());
        assertThat(mapper.candidates()).anyMatch(a -> a.getAssetId().equals(asset.assetId()));
        assertThat(mapper.cleaned(asset.assetId())).isEqualTo(1);
        assertThat(service.get(task, "default", asset.assetId()).cleanupPending()).isFalse();
        assertThat(mapper.candidates()).noneMatch(a -> a.getAssetId().equals(asset.assetId()));
        verifyNoInteractions(storage);
    }

    @Test
    void preparedAssetCannotBeCancelledButCompletionReplayWorksAndPrivateReadChecksStoredBytes() {
        String task = task("default");
        var asset = ready(task);
        conflict(() -> service.cancel(task, "default", asset.assetId()));
        assertThat(service.complete(task, "default", asset.assetId()).status()).isEqualTo("READY");
        var stored = mapper.find(task, "default", asset.assetId()).orElseThrow();
        when(storage.headObject(stored.getBucketName(), stored.getSourceKey())).thenReturn(new StoredObjectMetadata(123, "video/mp4", "etag"));
        var signed = new PresignedObjectRead("https://example.invalid/read", Instant.now().plusSeconds(300));
        when(storage.presignBrowserGetObject(eq(stored.getBucketName()), eq(stored.getSourceKey()), any())).thenReturn(signed);
        assertThat(service.read(task, "default", asset.assetId(), "ORIGINAL")).isSameAs(signed);
        when(storage.headObject(stored.getBucketName(), stored.getSourceKey())).thenReturn(new StoredObjectMetadata(122, "video/mp4", "etag"));
        conflict(() -> service.read(task, "default", asset.assetId(), "ORIGINAL"));
        verify(storage, times(1)).presignBrowserGetObject(anyString(), anyString(), any());
        conflict(() -> service.read(task, "default", asset.assetId(), "NONEXISTENT"));
    }

    @Test
    void partSignaturesValidateAllNumbersBeforeCallingStorageAndDoNotPersistShortUrls() {
        String task = task("default");
        var asset = uploading(task, PART_BYTES + 31);
        conflict(() -> service.sign(task, "default", asset.assetId(), List.of(1, 3)));
        conflict(() -> service.sign(task, "default", asset.assetId(), List.of(1, 1)));
        verifyNoInteractions(storage);
        var signed = new PresignedUploadPart(2, "https://example.invalid/put", Instant.now().plusSeconds(600));
        when(storage.presignUploadPart(anyString(), anyString(), eq(2), any())).thenReturn(signed);
        assertThat(service.sign(task, "default", asset.assetId(), List.of(2))).containsExactly(signed);
        assertThat(mapper.parts(asset.assetId())).isEmpty();
        assertThat(mapper.find(task, "default", asset.assetId()).orElseThrow().getStorageUploadId()).startsWith("storage-");
        properties.setBucket("changed-bucket");
        conflict(() -> service.sign(task, "default", asset.assetId(), List.of(1)));
        conflict(() -> service.complete(task, "default", asset.assetId()));
        verify(storage, times(1)).presignUploadPart(anyString(), anyString(), anyInt(), any());
    }

    @Test
    void taskAndOwnerCannotAccessOrChangeAnotherAsset() {
        String task = task("default");
        String otherTask = task("default");
        String otherOwnerTask = task("other-owner");
        var asset = uploading(task, 123);
        missing(() -> service.get(task, "other-owner", asset.assetId()));
        missing(() -> service.get(otherTask, "default", asset.assetId()));
        missing(() -> service.list(task, "other-owner", 0, 10));
        missing(() -> service.create(otherOwnerTask, "default", "wrong-owner", request(123, "a")));
        missing(() -> persistence.register(otherTask, "default", asset.assetId(), List.of(new Part(1, "wrong", 123))));
        missing(() -> service.complete(task, "other-owner", asset.assetId()));
        missing(() -> service.cancel(otherTask, "default", asset.assetId()));
        missing(() -> service.retry(otherTask, "default", asset.assetId()));
        assertThat(service.get(task, "default", asset.assetId()).status()).isEqualTo("UPLOADING");
        assertThat(mapper.parts(asset.assetId())).isEmpty();
        verifyNoInteractions(storage);
    }

    @Test
    void deletedTaskRejectsAdmissionQueriesAndLateLeaseFinish() {
        String task = task("default");
        var asset = service.create(task, "default", "deleted-key", request(123, "a"));
        assertThat(mapper.claim(asset.assetId(), "CREATING", "CREATING", "deleted-token")).isEqualTo(1);
        jdbc.update("UPDATE creator_task SET is_deleted=1 WHERE task_id=?", task);
        assertThat(mapper.find(task, "default", asset.assetId())).isEmpty();
        missing(() -> service.create(task, "default", "new-key", request(123, "a")));
        missing(() -> service.get(task, "default", asset.assetId()));
        missing(() -> service.list(task, "default", 0, 10));
        missing(() -> service.cancel(task, "default", asset.assetId()));
        assertThat(mapper.heartbeat(asset.assetId(), "deleted-token")).isZero();
        assertThat(mapper.finish(asset.assetId(), "deleted-token", "UPLOADING", "late-upload", null, null, null)).isZero();
        assertThat(mapper.candidates()).noneMatch(a -> a.getAssetId().equals(asset.assetId()));
        jdbc.update("UPDATE creator_creation_asset SET lease_token=NULL,lease_expires_at=NULL WHERE asset_id=?", asset.assetId());
        assertThat(mapper.claim(asset.assetId(), "CREATING", "CREATING", "late-token")).isZero();
        verifyNoInteractions(storage);
    }

    @Test
    void taskDeletionBlockersIncludeUnfinishedOrUncleanedAssetsButExcludeReadyAndCleanCancelledAssets() {
        String task = task("default");
        assertThat(tasks.countDeletionBlockers(task, "default")).isZero();
        var asset = service.create(task, "default", "deletion-key", request(123, "a"));
        assertThat(tasks.countDeletionBlockers(task, "default")).isEqualTo(1);
        assertThat(tasks.countDeletionBlockers(task, "another-owner")).isZero();
        // 这里只切换受控状态夹具，逐一验证真实删除 SQL 的业务条件。
        jdbc.update("UPDATE creator_creation_asset SET status='FAILED' WHERE asset_id=?", asset.assetId());
        assertThat(tasks.countDeletionBlockers(task, "default")).isEqualTo(1);
        jdbc.update("UPDATE creator_creation_asset SET status='CANCELLED',cleanup_pending=1,lease_expires_at=TIMESTAMPADD(SECOND,180,NOW(6)) WHERE asset_id=?", asset.assetId());
        assertThat(tasks.countDeletionBlockers(task, "default")).isEqualTo(1);
        assertThat(mapper.cleaned(asset.assetId())).isZero();
        jdbc.update("UPDATE creator_creation_asset SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE asset_id=?", asset.assetId());
        assertThat(mapper.cleaned(asset.assetId())).isEqualTo(1);
        assertThat(tasks.countDeletionBlockers(task, "default")).isZero();
        jdbc.update("UPDATE creator_creation_asset SET status='READY' WHERE asset_id=?", asset.assetId());
        assertThat(tasks.countDeletionBlockers(task, "default")).isZero();
        jdbc.update("UPDATE creator_creation_asset SET cleanup_pending=1 WHERE asset_id=?", asset.assetId());
        assertThat(tasks.countDeletionBlockers(task, "default")).isEqualTo(1);
        jdbc.update("UPDATE creator_creation_asset SET cleanup_pending=0 WHERE asset_id=?", asset.assetId());
        verifyNoInteractions(storage);
    }

    @Test
    void persistedObjectCleanupProtectsLiveLeaseAndExactReadyManifestButCollectsStaleFailedAndCancelledResults() {
        String task = task("default");
        var asset = service.create(task, "default", "object-key", request(123, "a"));
        String id = asset.assetId();
        String prefix = "private/assets/" + id + "/";
        assertThat(mapper.claim(id, "CREATING", "CREATING", "create-token")).isEqualTo(1);
        assertThat(mapper.registerObject(id, "create-token", "NORMALIZED", prefix + "not-preparing.mp4")).isZero();
        assertThat(mapper.finish(id, "create-token", "UPLOADED", "source-upload", null, null, null)).isEqualTo(1);
        assertThat(mapper.claim(id, "UPLOADED", "PREPARING", "old-token")).isEqualTo(1);
        assertThat(mapper.registerObject(id, "wrong-token", "NORMALIZED", prefix + "wrong.mp4")).isZero();
        assertThat(mapper.registerObject(id, "old-token", "NORMALIZED", prefix + "old.mp4")).isEqualTo(1);
        assertThat(mapper.pendingObjects()).noneMatch(o -> o.assetId().equals(id));
        jdbc.update("UPDATE creator_creation_asset_object SET cleanup_after=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE asset_id=?", id);
        assertThat(mapper.pendingObjects()).noneMatch(o -> o.assetId().equals(id));
        jdbc.update("UPDATE creator_creation_asset SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE asset_id=?", id);
        assertThat(mapper.registerObject(id, "old-token", "THUMBNAIL", prefix + "expired.jpg")).isZero();
        assertThat(mapper.claim(id, "PREPARING", "PREPARING", "replacement-token")).isEqualTo(1);
        assertThat(mapper.registerObject(id, "old-token", "THUMBNAIL", prefix + "stale.jpg")).isZero();
        assertThat(mapper.registerObject(id, "replacement-token", "NORMALIZED", prefix + "failed.mp4")).isEqualTo(1);
        assertThat(mapper.pendingObjects()).filteredOn(o -> o.assetId().equals(id))
                .extracting(CreationAssetMapper.PendingObject::objectKey).containsExactly(prefix + "old.mp4");
        assertThat(mapper.fail(id, "replacement-token", "DECODE_FAILED")).isEqualTo(1);
        // 失败并不表示网络写入已结束；新登记对象仍须等持久化的清理时间。
        assertThat(mapper.pendingObjects()).filteredOn(o -> o.assetId().equals(id))
                .extracting(CreationAssetMapper.PendingObject::objectKey).containsExactly(prefix + "old.mp4");
        jdbc.update("UPDATE creator_creation_asset_object SET cleanup_after=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE asset_id=?", id);
        assertThat(mapper.pendingObjects()).filteredOn(o -> o.assetId().equals(id))
                .extracting(CreationAssetMapper.PendingObject::objectKey).containsExactlyInAnyOrder(prefix + "old.mp4", prefix + "failed.mp4");

        service.retry(task, "default", id);
        assertThat(mapper.claim(id, "UPLOADED", "PREPARING", "ready-token")).isEqualTo(1);
        String keptKey = prefix + "normalized.mp4";
        assertThat(mapper.registerObject(id, "ready-token", "NORMALIZED", keptKey)).isEqualTo(1);
        // 与可用对象具有共同前缀仍不能保留；只有清单中的完整键能防止被清理。
        assertThat(mapper.registerObject(id, "ready-token", "THUMBNAIL", keptKey + ".orphan.jpg")).isEqualTo(1);
        String manifest = "[{\"role\":\"NORMALIZED\",\"objectKey\":\"" + keptKey + "\",\"contentType\":\"video/mp4\",\"bytes\":123,\"sha256\":\"" + "a".repeat(64) + "\"}]";
        assertThat(mapper.finish(id, "ready-token", "READY", null, "a".repeat(64), "{}", manifest)).isEqualTo(1);
        assertThat(mapper.registerObject(id, "ready-token", "LATE", prefix + "after-ready.mp4")).isZero();
        assertThat(mapper.pendingObjects()).filteredOn(o -> o.assetId().equals(id))
                .extracting(CreationAssetMapper.PendingObject::objectKey).containsExactlyInAnyOrder(prefix + "old.mp4", prefix + "failed.mp4");
        jdbc.update("UPDATE creator_creation_asset_object SET cleanup_after=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE asset_id=?", id);
        var pending = mapper.pendingObjects().stream().filter(o -> o.assetId().equals(id)).toList();
        assertThat(pending).extracting(CreationAssetMapper.PendingObject::objectKey)
                .containsExactlyInAnyOrder(prefix + "old.mp4", prefix + "failed.mp4", keptKey + ".orphan.jpg");
        assertThat(pending).allSatisfy(o -> assertThat(o.bucketName()).isEqualTo(properties.getBucket()));
        for (var object : pending) {
            mapper.objectCleaned(object.assetId(), object.leaseToken(), object.role());
            mapper.objectCleaned(object.assetId(), object.leaseToken(), object.role());
        }
        assertThat(mapper.pendingObjects()).noneMatch(o -> o.assetId().equals(id));
        assertThat(jdbc.queryForObject("SELECT cleanup_done FROM creator_creation_asset_object WHERE asset_id=? AND lease_token='ready-token' AND role='NORMALIZED'", Integer.class, id)).isZero();

        var cancelled = service.create(task, "default", "cancelled-object-key", request(123, "a"));
        String cancelledId = cancelled.assetId();
        assertThat(mapper.claim(cancelledId, "CREATING", "PREPARING", "cancelled-token")).isEqualTo(1);
        assertThat(mapper.registerObject(cancelledId, "cancelled-token", "NORMALIZED", prefix + "cancelled.mp4")).isEqualTo(1);
        service.cancel(task, "default", cancelledId);
        assertThat(mapper.registerObject(cancelledId, "cancelled-token", "THUMBNAIL", prefix + "late-cancelled.jpg")).isZero();
        assertThat(mapper.cleaned(cancelledId)).isZero();
        assertThat(mapper.pendingObjects()).noneMatch(o -> o.assetId().equals(cancelledId));
        jdbc.update("UPDATE creator_creation_asset SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE asset_id=?", cancelledId);
        assertThat(mapper.cleaned(cancelledId)).isZero();
        assertThat(mapper.pendingObjects()).noneMatch(o -> o.assetId().equals(cancelledId));
        jdbc.update("UPDATE creator_creation_asset_object SET cleanup_after=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE asset_id=?", cancelledId);
        assertThat(mapper.pendingObjects()).filteredOn(o -> o.assetId().equals(cancelledId))
                .extracting(CreationAssetMapper.PendingObject::objectKey).containsExactly(prefix + "cancelled.mp4");
        assertThat(mapper.cleaned(cancelledId)).isZero();
        mapper.objectCleaned(cancelledId, "cancelled-token", "NORMALIZED");
        assertThat(mapper.cleaned(cancelledId)).isEqualTo(1);
        assertThat(mapper.pendingObjects()).noneMatch(o -> o.assetId().equals(cancelledId));
        verifyNoInteractions(storage);
    }

    private Snapshot uploading(String task, long size) {
        var created = service.create(task, "default", UUID.randomUUID().toString(), request(size, "a"));
        String token = UUID.randomUUID().toString();
        assertThat(mapper.claim(created.assetId(), "CREATING", "CREATING", token)).isEqualTo(1);
        assertThat(mapper.finish(created.assetId(), token, "UPLOADING", "storage-" + created.assetId(), null, null, null)).isEqualTo(1);
        return service.get(task, "default", created.assetId());
    }

    private Snapshot ready(String task) {
        var asset = uploading(task, 123);
        persistence.register(task, "default", asset.assetId(), List.of(new Part(1, "one", 123)));
        service.complete(task, "default", asset.assetId());
        assertThat(mapper.claim(asset.assetId(), "COMPLETING", "COMPLETING", "complete-token")).isEqualTo(1);
        assertThat(mapper.finish(asset.assetId(), "complete-token", "UPLOADED", null, null, null, null)).isEqualTo(1);
        assertThat(mapper.claim(asset.assetId(), "UPLOADED", "PREPARING", "prepare-token")).isEqualTo(1);
        assertThat(mapper.finish(asset.assetId(), "prepare-token", "READY", null, "a".repeat(64), "{\"durationMs\":1000}", "[]")).isEqualTo(1);
        return service.get(task, "default", asset.assetId());
    }

    private static Create request(long bytes, String hashCharacter) {
        return new Create("VIDEO_CLIP", "same-name.mp4", "video/mp4", bytes, hashCharacter.repeat(64));
    }

    private static String task(String owner) {
        String task = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO creator_task(task_id,user_id,task_name,video_type,status,planning_skipped) VALUES (?,?, '隔离素材测试','知识分享','DRAFT',1)", task, owner);
        return task;
    }

    private static int count(String table, String task) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id=?", Integer.class, task);
    }

    private static void conflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
    }

    private static void missing(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));
    }
}
