package com.link.linkagent.creator.media.upload.service;

import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.config.ObjectStorageProperties;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.CreateMediaUploadRequest;
import com.link.linkagent.creator.media.upload.model.MediaUploadRecord;
import com.link.linkagent.creator.media.workflow.CreatorMediaWorkflowGateService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MediaUploadService 关键状态机保护测试。
 * <p>
 * P0 测试策略：只针对"错误状态被拒绝"这个最高风险场景编写回归测试，
 * 不追求覆盖所有正常路径和边界条件。正常流程通过集成测试和手工验证覆盖。
 * <p>
 * 测试重点：
 * <ul>
 *   <li>VERIFYING 状态下禁止签名（signParts）— 防止在 OSS 合并期间写入新分片</li>
 *   <li>VERIFYING 状态下禁止取消（abortUpload）— 防止 OSS 合并成功但被误标为取消</li>
 * </ul>
 */
class MediaUploadServiceTest {

    @Test
    void shouldCreateIndependentVersionWithoutReadingOrResettingCurrentDraft() {
        var mapper = mock(MediaUploadMapper.class);
        when(mapper.countTaskByOwner("task-1", "default")).thenReturn(1);
        var storage = mock(ObjectStorageService.class);
        when(storage.createMultipartUpload(anyString(), anyString()))
                .thenReturn(new com.link.linkagent.creator.media.storage.MultipartUploadHandle("new-storage-upload"));
        var persistence = mock(MediaUploadPersistenceService.class);
        when(persistence.saveCreatedUpload(any(), any(), anyLong())).thenAnswer(call -> call.getArgument(1));
        var properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        var service = new MediaUploadService(properties, configuredStorageProperties(), storage, mapper,
                persistence, mock(CreatorMediaWorkflowGateService.class));

        var response = service.createUpload("default", "task-1", "new-key",
                new CreateMediaUploadRequest("第二版", "new.mp4", 1024L, "video/mp4", 2L));

        var draft = org.mockito.ArgumentCaptor.forClass(com.link.linkagent.creator.media.upload.model.DraftVideoRecord.class);
        verify(persistence).saveCreatedUpload(draft.capture(), any(), anyLong());
        assertThat(draft.getValue().versionId()).isEqualTo(response.versionId());
        // 应用层仅生成身份；真正的展示序号必须由事务分配。
        assertThat(draft.getValue().versionNo()).isZero();
        verify(mapper, never()).findDraftVideo(anyString(), anyString());
        verify(storage, never()).deleteObject(anyString(), anyString());
    }

    @Test
    void shouldRejectNewUploadForBoundTaskBeforeCreatingStorageSession() {
        var mapper = mock(MediaUploadMapper.class);
        when(mapper.countTaskByOwner("task-1", "default")).thenReturn(1);
        when(mapper.countPublishingFacts("task-1", "default")).thenReturn(1);
        var storage = mock(ObjectStorageService.class);
        var properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        var service = new MediaUploadService(properties, configuredStorageProperties(), storage, mapper,
                mock(MediaUploadPersistenceService.class), mock(CreatorMediaWorkflowGateService.class));

        assertThatThrownBy(() -> service.createUpload("default", "task-1", "new-key",
                new CreateMediaUploadRequest("新版本", "new.mp4", 1024L, "video/mp4", 2L)))
                .isInstanceOf(ResponseStatusException.class);
        org.mockito.Mockito.verifyNoInteractions(storage);
    }

    @Test
    void concurrentIdempotentWinnerMustOnlyAbortLosingStorageUpload() {
        var mapper = mock(MediaUploadMapper.class);
        when(mapper.countTaskByOwner("task-1", "default")).thenReturn(1);
        var winner = new java.util.concurrent.atomic.AtomicReference<MediaUploadRecord>();
        when(mapper.findUploadByIdempotency(anyString(), anyString(), anyString()))
                .thenAnswer(call -> Optional.ofNullable(winner.get()));
        var storage = mock(ObjectStorageService.class);
        when(storage.createMultipartUpload(anyString(), anyString()))
                .thenReturn(new com.link.linkagent.creator.media.storage.MultipartUploadHandle("losing-storage-upload"));
        var persistence = mock(MediaUploadPersistenceService.class);
        when(persistence.saveCreatedUpload(any(), any(), anyLong())).thenAnswer(call -> {
            MediaUploadRecord value = call.getArgument(1);
            var saved = new MediaUploadRecord(null, "winning-session", "winning-version", value.taskId(), value.ownerId(),
                    "winning-storage", "winning-object", value.contentType(), value.expectedSize(), value.fileFingerprint(),
                    value.partSize(), value.totalParts(), "CREATED", value.idempotencyKey(), null, value.expiresAt(), null, null, null);
            winner.set(saved);
            return saved;
        });
        var properties = new CreatorMediaProperties();
        properties.setEnabled(true);
        var service = new MediaUploadService(properties, configuredStorageProperties(), storage, mapper,
                persistence, mock(CreatorMediaWorkflowGateService.class));
        var result = service.createUpload("default", "task-1", "same-key",
                new CreateMediaUploadRequest("新版", "new.mp4", 1024L, "video/mp4", 2L));
        assertThat(result.uploadSessionId()).isEqualTo("winning-session");
        verify(storage).abortMultipartUpload(org.mockito.ArgumentMatchers.eq("test-media-bucket"),
                anyString(), org.mockito.ArgumentMatchers.eq("losing-storage-upload"));
        verify(storage, never()).deleteObject(anyString(), anyString());
    }

    /**
     * 测试：VERIFYING 状态下调用 signParts 应返回 409 CONFLICT。
     * <p>
     * VERIFYING 表示 CompleteMultipartUpload 已发送但尚未确认，
     * 此时不能再为分片签名，否则会导致并发修改。
     * <p>
     * 验证点：
     * 1. 抛出 ResponseStatusException
     * 2. HTTP 状态码为 409 CONFLICT
     * 3. markUploadUploading 从未被调用（在状态校验阶段就已被拦截）
     */
    @Test
    void shouldRejectPartSigningWhileUploadIsVerifying() {
        // 创建 Mapper 的 Mock：查到的上传会话状态为 VERIFYING
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        when(mapper.findUpload("task-1", "default", "upload-1"))
                .thenReturn(Optional.of(upload("VERIFYING"))); // Mock 返回 VERIFYING 状态的上传会话
        // 创建 Service（其他依赖为默认空配置或 Mock）
        MediaUploadService service = service(mapper);

        // 调用 signParts，预期被拦截抛异常
        ResponseStatusException exception = catchThrowableOfType(
                () -> service.signParts("default", "task-1", "upload-1", List.of(1)),
                ResponseStatusException.class
        );

        // 确认返回 409（状态冲突）
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 确认数据库写操作从未发生（状态拦截在 Mapper 调用之前）
        verify(mapper, never()).markUploadUploading("task-1", "default", "upload-1");
    }

    /**
     * 测试：VERIFYING 状态下调用 abortUpload 应返回 409 CONFLICT。
     * <p>
     * VERIFYING 状态下 OSS 的 CompleteMultipartUpload 可能已经成功，
     * 此时取消会导致 OSS 上存在完整对象但数据库标记为取消，数据不一致。
     * 因此必须在 Service 层拒绝取消请求。
     * <p>
     * 验证点：
     * 1. 抛出 ResponseStatusException
     * 2. HTTP 状态码为 409 CONFLICT
     * 3. markUploadAborted 从未被调用
     */
    @Test
    void shouldRejectAbortWhileUploadIsVerifying() {
        // 创建 Mapper 的 Mock：查到的上传会话状态为 VERIFYING
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        when(mapper.findUpload("task-1", "default", "upload-1"))
                .thenReturn(Optional.of(upload("VERIFYING"))); // Mock 返回 VERIFYING 状态
        // 创建持久化服务的 Mock（用于验证 Abort 操作未被调用）
        MediaUploadPersistenceService persistenceService = mock(MediaUploadPersistenceService.class);
        // 手动构造 Service，因为需要注入 Mock 的 persistenceService
        MediaUploadService service = new MediaUploadService(
                new CreatorMediaProperties(),
                new ObjectStorageProperties(),
                mock(ObjectStorageService.class), // OSS 操作在此测试场景不会被调用
                mapper,
                persistenceService,
                mock(CreatorMediaWorkflowGateService.class)
        );

        // 调用 abortUpload，预期被拦截抛异常
        ResponseStatusException exception = catchThrowableOfType(
                () -> service.abortUpload("default", "task-1", "upload-1"),
                ResponseStatusException.class
        );

        // 确认返回 409（状态冲突）
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 确认持久化层的 Abort 操作从未被调用
        verify(persistenceService, never()).markUploadAborted(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRejectUploadBeforeCreatingStorageSessionWhenPrePublishIsNotConfirmed() {
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        MediaUploadPersistenceService persistenceService = mock(MediaUploadPersistenceService.class);
        CreatorMediaWorkflowGateService mediaWorkflowGateService = mock(CreatorMediaWorkflowGateService.class);
        CreatorMediaProperties mediaProperties = new CreatorMediaProperties();
        mediaProperties.setEnabled(true);
        ObjectStorageProperties storageProperties = configuredStorageProperties();
        when(mapper.countTaskByOwner("task-1", "default")).thenReturn(1);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "请先确认发布方案"))
                .when(mediaWorkflowGateService)
                .ensurePrePublishConfirmed("task-1", "default", "成片试映");
        MediaUploadService service = new MediaUploadService(
                mediaProperties,
                storageProperties,
                storageService,
                mapper,
                persistenceService,
                mediaWorkflowGateService
        );

        ResponseStatusException exception = catchThrowableOfType(
                () -> service.createUpload(
                        "default",
                        "task-1",
                        "idempotency-1",
                        new CreateMediaUploadRequest("V1 初剪", "source.mp4", 1024L, "video/mp4", 1L)
                ),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(storageService, never()).createMultipartUpload(anyString(), anyString());
        verify(mapper, never()).findUploadByIdempotency(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRestoreCurrentUploadFromServerState() {
        MediaUploadMapper mapper = mock(MediaUploadMapper.class);
        when(mapper.findCurrentUpload("task-1", "default"))
                .thenReturn(Optional.of(upload("UPLOADING")));
        when(mapper.listParts("upload-1")).thenReturn(List.of());

        var response = service(mapper).getCurrentUpload("default", "task-1");

        assertThat(response.uploadSessionId()).isEqualTo("upload-1");
        assertThat(response.idempotencyKey()).isEqualTo("idempotency-1");
        assertThat(response.status()).isEqualTo("UPLOADING");
    }

    /**
     * 创建 MediaUploadService 的便捷工厂方法。
     * 所有需要默认行为的依赖使用 Mock 代替，Mapper 由参数注入（方便控制 findUpload 返回值）。
     *
     * @param mapper Mock 的 Mapper（由测试方法控制行为）
     * @return 注入完成的 MediaUploadService 实例
     */
    private MediaUploadService service(MediaUploadMapper mapper) {
        return new MediaUploadService(
                new CreatorMediaProperties(),        // 使用默认属性（总开关未开启，但测试不校验配置）
                new ObjectStorageProperties(),       // 使用默认属性（凭证为空，但测试不触发 OSS 调用）
                mock(ObjectStorageService.class),    // Mock OSS 服务（测试不涉及 OSS 调用）
                mapper,                              // 可控制的 Mapper Mock
                mock(MediaUploadPersistenceService.class), // Mock 持久化服务
                mock(CreatorMediaWorkflowGateService.class)
        );
    }

    private ObjectStorageProperties configuredStorageProperties() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setAccessKey("test-access-key");
        properties.setSecretKey("test-secret-key");
        properties.setBucket("test-media-bucket");
        return properties;
    }

    /**
     * 创建指定状态的上传会话记录（测试数据工厂）。
     * <p>
     * 除 status 外所有字段使用固定值，这样测试只关注状态差异。
     * 对象键格式与 MediaUploadService.buildObjectKey() 输出一致。
     * fileFingerprint 使用 64 个 'a' 填充（足够满足 SHA-256 十六进制 64 字符长度）。
     *
     * @param status 上传会话状态（如 VERIFYING、UPLOADING 等）
     * @return 填充完成的 MediaUploadRecord 测试数据
     */
    private MediaUploadRecord upload(String status) {
        return new MediaUploadRecord(
                1L,                // id：自增主键
                "upload-1",        // uploadSessionId
                "version-1",       // versionId
                "task-1",          // taskId
                "default",         // ownerId（P0 固定值）
                "storage-upload-1",// storageUploadId（OSS Upload ID）
                "users/default/tasks/task-1/versions/version-1/attempts/upload-1/original/source.mp4", // objectKey
                "video/mp4",       // contentType
                16L * 1024 * 1024, // expectedSize：16 MiB（一个分片大小）
                "a".repeat(64),    // fileFingerprint：64 个 'a'（SHA-256 十六进制长度）
                16 * 1024 * 1024,  // partSize：16 MiB
                1,                 // totalParts：1 个分片（简化测试）
                status,            // 状态：由参数决定（测试的核心变量）
                "idempotency-1",   // idempotencyKey
                null,              // failureMessage（无失败）
                LocalDateTime.now().plusHours(1), // expiresAt：1 小时后过期（未过期）
                null,              // completedAt（未完成）
                LocalDateTime.now(), // createTime
                LocalDateTime.now()  // updateTime
        );
    }
}
