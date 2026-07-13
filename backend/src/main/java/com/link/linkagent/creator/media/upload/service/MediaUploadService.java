package com.link.linkagent.creator.media.upload.service;

import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.config.ObjectStorageProperties;
import com.link.linkagent.creator.media.storage.CompletedUploadPart;
import com.link.linkagent.creator.media.storage.MediaStorageException;
import com.link.linkagent.creator.media.storage.MultipartUploadHandle;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.storage.PresignedUploadPart;
import com.link.linkagent.creator.media.storage.StoredObjectMetadata;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.CompletedMediaUploadPartRequest;
import com.link.linkagent.creator.media.upload.model.CreateMediaUploadRequest;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.media.upload.model.DraftVideoResponse;
import com.link.linkagent.creator.media.upload.model.DraftVideoStatus;
import com.link.linkagent.creator.media.upload.model.MediaUploadPartRecord;
import com.link.linkagent.creator.media.upload.model.MediaUploadPartResponse;
import com.link.linkagent.creator.media.upload.model.MediaUploadPartSignResponse;
import com.link.linkagent.creator.media.upload.model.MediaUploadPartsCompleteRequest;
import com.link.linkagent.creator.media.upload.model.MediaUploadRecord;
import com.link.linkagent.creator.media.upload.model.MediaUploadResponse;
import com.link.linkagent.creator.media.upload.model.MediaUploadStatus;
import com.link.linkagent.creator.media.upload.model.PresignedMediaUploadPartResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 成片分片上传应用服务。
 * <p>
 * 浏览器直接把文件分片 PUT 到 OSS；本服务只负责可信归属、短时签名、ETag 登记、
 * 完成校验和 MySQL 状态收敛，因此 1.5 GB 文件不会进入 JVM 堆或 Spring MultipartFile。
 * <p>
 * 关键安全约束：ownerId 不由客户端传入，而是从 {@link com.link.linkagent.creator.media.access.filter.MediaAccessSessionFilter}
 * 注入的 request 属性中读取，确保只有通过媒体访问口令认证的会话才能操作上传。
 */
@Service
// 只有 creator.media.enabled=true 时才创建该 Bean，避免未配置 OSS 凭证的部署启动失败
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class MediaUploadService {

    // P0 只支持单一版本号 V1，后续版本对比阶段再扩展多版本
    private static final int P0_VERSION_NO = 1;
    // 失败原因摘要上限：对应 creator_media_upload.failure_message 列长度 500
    private static final int MAX_FAILURE_MESSAGE_LENGTH = 500;
    // VERIFYING 状态恢复等待时间：Complete 请求可能在 OSS 端成功但网络丢包，
    // 等待 5 分钟后允许客户端重试，避免重复扣费
    private static final Duration VERIFYING_RECOVERY_DELAY = Duration.ofMinutes(5);

    // 媒体能力配置（上传限制、分片大小、签名 TTL 等）
    private final CreatorMediaProperties mediaProperties;
    // 对象存储连接配置（Endpoint、Bucket 等）
    private final ObjectStorageProperties storageProperties;
    // 对象存储操作抽象（P0 使用 S3 兼容实现，将来可切换为官方 OSS SDK）
    private final ObjectStorageService objectStorageService;
    // MyBatis 数据访问层
    private final MediaUploadMapper mediaUploadMapper;
    // 数据库事务边界聚合（保证成片、上传会话、分片记录的原子写入）
    private final MediaUploadPersistenceService persistenceService;

    // 构造器注入，Spring 自动装配所有依赖
    public MediaUploadService(CreatorMediaProperties mediaProperties,
                              ObjectStorageProperties storageProperties,
                              ObjectStorageService objectStorageService,
                              MediaUploadMapper mediaUploadMapper,
                              MediaUploadPersistenceService persistenceService) {
        this.mediaProperties = mediaProperties;
        this.storageProperties = storageProperties;
        this.objectStorageService = objectStorageService;
        this.mediaUploadMapper = mediaUploadMapper;
        this.persistenceService = persistenceService;
    }

    /**
     * 创建成片分片上传会话。
     * <p>
     * 支持幂等重试：同一任务同一 Idempotency-Key 和相同文件指纹，重复请求返回原会话而非新建。
     * 文件指纹是文件名、大小和最后修改时间的 SHA-256 摘要，防止不同文件共用同一幂等键。
     *
     * @param ownerId        可信媒体归属（来自 Filter 注入的 request 属性，非客户端传入）
     * @param taskId         创作任务 ID
     * @param idempotencyKey 浏览器生成的幂等键（通常基于 taskId + fileFingerprint）
     * @param request        创建请求（版本名、文件名、大小、媒体类型、最后修改时间）
     * @return 上传会话快照（含 uploadSessionId、分片参数等），前端据此开始分片上传
     */
    public MediaUploadResponse createUpload(String ownerId,
                                            String taskId,
                                            String idempotencyKey,
                                            CreateMediaUploadRequest request) {
        // 先校验所有配置是否就绪，避免执行到一半才发现缺配置
        ensureConfigurationReady();
        // 去除首尾空白，防止前端误传空格导致后续比对失败
        String normalizedTaskId = taskId.trim();
        String normalizedIdempotencyKey = idempotencyKey.trim();
        String normalizedFileName = normalizeFileName(request.fileName());
        // 校验文件大小不超过部署配置的上限（默认 1.5GB，可通过环境变量覆盖）
        if (request.fileSize() > mediaProperties.getMaxFileBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "视频文件超过当前部署配置的大小上限");
        }
        // 计算文件指纹：文件名 + 大小 + 最后修改时间的 SHA-256，用于续传对账和幂等校验
        String fingerprint = fileFingerprint(normalizedFileName, request.fileSize(), request.lastModified());

        // 先查幂等键是否已有现存会话，避免重复创建 OSS Multipart Upload（每次创建都可能产生存储费用）
        OptionalUpload optionalUpload = findIdempotentUpload(
                normalizedTaskId,
                ownerId,
                normalizedIdempotencyKey,
                fingerprint
        );
        if (optionalUpload.found()) {
            // 幂等命中：直接返回已有会话，不发任何 OSS 请求
            return toUploadResponse(optionalUpload.upload());
        }

        // 校验任务是否存在且属于当前 owner（P0 ownerId 固定为 default）
        if (mediaUploadMapper.countTaskByOwner(normalizedTaskId, ownerId) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在或不属于当前媒体会话");
        }

        // 查是否已有该任务的成片记录：没有则新建，有则需判断是否能复用
        DraftVideoRecord currentDraft = mediaUploadMapper.findDraftVideo(normalizedTaskId, ownerId).orElse(null);
        boolean createDraft = currentDraft == null;
        if (!createDraft && !canReuseDraft(currentDraft.status())) {
            // 成片正在上传中或已完成，不允许覆盖
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前任务已经存在上传中或已完成的成片");
        }

        // 版本 ID：新建时生成 UUID，复用时沿用已有版本 ID（P0 只有一个版本）
        String versionId = createDraft ? UUID.randomUUID().toString() : currentDraft.versionId();
        // 上传会话 ID：每次创建都生成新 UUID，区分不同上传尝试
        String uploadSessionId = UUID.randomUUID().toString();
        // 后端生成的对象键：格式为 users/{ownerId}/tasks/{taskId}/versions/{versionId}/attempts/{uploadSessionId}/original/source.mp4
        // 前端不得传入或构造对象键，避免路径注入
        String objectKey = buildObjectKey(ownerId, normalizedTaskId, versionId, uploadSessionId);
        // 调用 OSS 创建分片上传，获取 storageUploadId（后续签名和完成都需要）
        MultipartUploadHandle storageUpload;
        try {
            storageUpload = objectStorageService.createMultipartUpload(objectKey, request.contentType().toLowerCase(Locale.ROOT));
        } catch (MediaStorageException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "对象存储暂时不可用，无法创建上传会话");
        }

        // 计算分片参数
        LocalDateTime now = LocalDateTime.now();
        int partSize = mediaProperties.getUpload().getPartSizeBytes(); // 默认 16 MiB
        // 总分片数：向上取整，最后一个分片可以小于 partSize
        int totalParts = Math.toIntExact((request.fileSize() + partSize - 1L) / partSize);
        // 构造成片记录（复用已有记录时保留原 createTime）
        DraftVideoRecord draftVideo = new DraftVideoRecord(
                createDraft ? null : currentDraft.id(),       // 新建时为 null，MyBatis 会使用自增主键
                versionId,
                normalizedTaskId,
                ownerId,
                P0_VERSION_NO,                                  // P0 固定版本号为 1
                request.versionName().trim(),
                normalizedFileName,
                storageProperties.getBucket(),                  // 桶名从配置读取
                objectKey,                                     // 后端生成的对象键
                request.contentType().toLowerCase(Locale.ROOT),
                request.fileSize(),
                DraftVideoStatus.UPLOADING.name(),             // 初始状态：上传中
                createDraft ? null : currentDraft.createTime(),
                createDraft ? null : currentDraft.updateTime()
        );
        // 构造上传会话记录
        MediaUploadRecord upload = new MediaUploadRecord(
                null,                                          // 自增主键，插入时为空
                uploadSessionId,
                versionId,
                normalizedTaskId,
                ownerId,
                storageUpload.uploadId(),                       // OSS 返回的 Upload ID
                objectKey,
                request.contentType().toLowerCase(Locale.ROOT),
                request.fileSize(),                             // 客户端声明的预期大小
                fingerprint,                                    // 文件指纹，用于续传对账
                partSize,
                totalParts,
                MediaUploadStatus.CREATED.name(),               // 初始状态：已创建（等待首次签名）
                normalizedIdempotencyKey,
                null,                                          // 暂无失败原因
                now.plus(mediaProperties.getUpload().getAbandonedTtl()), // 过期时间：默认创建后 24 小时
                null,                                          // 尚未完成
                null,                                          // createTime 由数据库默认值填充
                null                                           // updateTime 由数据库默认值填充
        );

        try {
            // 在事务内原子写入成片记录和上传会话记录
            MediaUploadRecord replacedUpload = persistenceService.saveCreatedUpload(
                    draftVideo,
                    createDraft,
                    createDraft ? null : currentDraft.objectKey(), // 复用成片时传入原对象键，用于行级锁定位
                    upload
            );
            if (replacedUpload != null) {
                // 新旧尝试使用不同对象键（每次 attempt 不同 uploadSessionId）；
                // 事务提交后再清理旧尝试的 OSS 资源，清理失败由 Bucket 生命周期兜底
                safeAbort(replacedUpload.objectKey(), replacedUpload.storageUploadId());
                safeDelete(replacedUpload.objectKey());
            }
        } catch (RuntimeException exception) {
            // 数据库写入失败时，必须清理已创建的 OSS Multipart Upload，避免产生孤立资源
            safeAbort(objectKey, storageUpload.uploadId());
            if (exception instanceof DataIntegrityViolationException) {
                // 唯一键冲突可能是因为并发创建了相同幂等键的会话，尝试查找并返回
                OptionalUpload concurrentUpload = findIdempotentUpload(
                        normalizedTaskId,
                        ownerId,
                        normalizedIdempotencyKey,
                        fingerprint
                );
                if (concurrentUpload.found()) {
                    return toUploadResponse(concurrentUpload.upload());
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前任务的成片上传状态已经变化，请刷新后重试");
            }
            if (exception instanceof IllegalStateException) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
            }
            throw exception; // 其他未预期异常向上抛，由全局异常处理器统一处理
        }
        return toUploadResponse(upload);
    }

    /**
     * 获取上传会话快照，供前端页面刷新后恢复续传状态。
     * 同时检查是否已过期，若过期则标记并返回 GONE 状态。
     */
    public MediaUploadResponse getUpload(String ownerId, String taskId, String uploadSessionId) {
        MediaUploadRecord upload = requireUpload(ownerId, taskId, uploadSessionId);
        expireIfNecessary(upload); // 读取时顺带检查过期，避免过期会话仍被使用
        return toUploadResponse(upload);
    }

    /**
     * 列出已登记的分片列表，供前端续传时跳过已完成分片。
     */
    public List<MediaUploadPartResponse> listParts(String ownerId,
                                                   String taskId,
                                                   String uploadSessionId) {
        MediaUploadRecord upload = requireUpload(ownerId, taskId, uploadSessionId);
        expireIfNecessary(upload);
        return mediaUploadMapper.listParts(upload.uploadSessionId())
                .stream()
                .map(this::toPartResponse) // 将数据库记录转换为 API 响应
                .toList();
    }

    /**
     * 为指定分片批量生成短时预签名 PUT URL。
     * <p>
     * 预签名 URL 包含分片序号和上传 ID 的签名，浏览器拿到后直接 PUT 到 OSS，
     * 不经过本服务中转，因此大文件不会进入 JVM 堆。
     * 签名有效期为 15 分钟（可配置），浏览器必须在过期前完成分片上传。
     */
    public MediaUploadPartSignResponse signParts(String ownerId,
                                                 String taskId,
                                                 String uploadSessionId,
                                                 List<Integer> partNumbers) {
        MediaUploadRecord upload = requireUpload(ownerId, taskId, uploadSessionId);
        expireIfNecessary(upload);
        ensureStatusAllowsUpload(upload.status()); // 只允许 CREATED 和 UPLOADING 状态签名

        // 去重校验：同一个分片不能重复申请签名
        Set<Integer> uniquePartNumbers = new HashSet<>(partNumbers);
        if (uniquePartNumbers.size() != partNumbers.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分片序号不能重复");
        }
        if (partNumbers.size() > mediaProperties.getUpload().getMaxSignBatch()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单次申请的分片签名数量超过配置上限");
        }

        // 将状态从 CREATED 切换到 UPLOADING，表示已有分片开始上传
        if (mediaUploadMapper.markUploadUploading(
                upload.taskId(),
                upload.ownerId(),
                upload.uploadSessionId()
        ) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前上传状态已经结束，不能继续申请分片签名");
        }

        // 逐个调用 OSS Presigner 生成预签名 URL
        List<PresignedMediaUploadPartResponse> responses = new ArrayList<>(partNumbers.size());
        try {
            for (Integer partNumber : partNumbers) {
                // 分片序号不能超过总分片数
                if (partNumber > upload.totalParts()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分片序号超过当前文件的总分片数");
                }
                // 调用 S3 Presigner 生成单次 PUT 的短时签名 URL
                PresignedUploadPart signedPart = objectStorageService.presignUploadPart(
                        upload.objectKey(),
                        upload.storageUploadId(),
                        partNumber,
                        mediaProperties.getUpload().getPresignTtl() // 默认 15 分钟有效期
                );
                responses.add(new PresignedMediaUploadPartResponse(
                        signedPart.partNumber(),
                        signedPart.url(),       // 预签名 URL（含认证参数，不得写入日志）
                        signedPart.expiresAt()  // URL 过期时间，前端据此刷新签名
                ));
            }
        } catch (MediaStorageException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "对象存储暂时不可用，无法生成分片签名");
        }

        return new MediaUploadPartSignResponse(responses);
    }

    /**
     * 批量登记浏览器已完成上传的分片。
     * <p>
     * 浏览器 PUT 成功后从响应头拿到 ETag，调用本接口登记。
     * ETag 由 OSS 生成，本服务原样保存，完成上传时原样回传给 OSS。
     * 支持 ON DUPLICATE KEY UPDATE，同一分片可重复登记（续传场景）。
     */
    public List<MediaUploadPartResponse> registerCompletedParts(String ownerId,
                                                                String taskId,
                                                                String uploadSessionId,
                                                                MediaUploadPartsCompleteRequest request) {
        MediaUploadRecord upload = requireUpload(ownerId, taskId, uploadSessionId);
        expireIfNecessary(upload);
        ensureStatusAllowsUpload(upload.status());

        // 校验本批次内分片序号不重复
        Set<Integer> uniquePartNumbers = new HashSet<>();
        LocalDateTime completedAt = LocalDateTime.now();
        List<MediaUploadPartRecord> records = new ArrayList<>(request.parts().size());
        for (CompletedMediaUploadPartRequest part : request.parts()) {
            if (!uniquePartNumbers.add(part.partNumber())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同一批次不能重复登记分片");
            }
            validatePart(upload, part); // 校验分片序号不越界、大小与预期一致
            records.add(new MediaUploadPartRecord(
                    null,
                    upload.uploadSessionId(),
                    part.partNumber(),
                    part.etag().trim(),  // 去除首尾空白，OSS ETag 对空格敏感
                    part.partSize(),
                    completedAt,
                    null,               // createTime 由数据库默认值填充
                    null                // updateTime 由数据库默认值填充
            ));
        }
        try {
            // 在事务内原子写入所有分片记录 + 更新上传会话状态
            persistenceService.saveCompletedParts(taskId, ownerId, uploadSessionId, records);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
        // 返回最新分片列表（含本次新登记的），供前端确认
        return listParts(ownerId, taskId, uploadSessionId);
    }

    /**
     * 完成上传：将所有已登记分片提交给 OSS 合并为完整对象，并校验结果。
     * <p>
     * 这是整个上传流程中最关键的一步：
     * 1. 校验所有分片已登记且序号连续、大小正确
     * 2. 调用 OSS CompleteMultipartUpload 合并分片
     * 3. HeadObject 确认对象大小和媒体类型
     * 4. 校验通过后更新数据库状态为 COMPLETED / UPLOADED
     * <p>
     * VERIFYING 状态容错：Complete 请求可能在 OSS 端成功但响应在网络中丢失，
     * 此时 HeadObject 若返回正常对象则直接标记完成，避免重复扣费。
     */
    public DraftVideoResponse completeUpload(String ownerId,
                                             String taskId,
                                             String uploadSessionId) {
        MediaUploadRecord upload = requireUpload(ownerId, taskId, uploadSessionId);
        // 已完成则直接返回事实，幂等保证
        if (MediaUploadStatus.COMPLETED.name().equals(upload.status())) {
            return toDraftVideoResponse(requireDraft(ownerId, taskId));
        }
        expireIfNecessary(upload);
        if (MediaUploadStatus.VERIFYING.name().equals(upload.status())) {
            // VERIFYING 状态恢复：上轮 Complete 请求可能已成功，先 HeadObject 确认
            DraftVideoResponse recovered = recoverVerifyingUpload(upload);
            if (recovered != null) {
                return recovered; // 恢复成功，直接返回
            }
        } else {
            ensureStatusAllowsComplete(upload.status()); // 只允许 UPLOADING 状态完成
        }

        // 校验所有分片已登记、序号连续、总大小一致
        List<MediaUploadPartRecord> parts = mediaUploadMapper.listParts(upload.uploadSessionId());
        validateCompletePartSet(upload, parts);
        // 先标记为 VERIFYING，阻止并发重复完成请求
        if (mediaUploadMapper.markUploadVerifying(
                upload.taskId(),
                upload.ownerId(),
                upload.uploadSessionId()
        ) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前上传状态已经结束，不能完成上传");
        }

        StoredObjectMetadata metadata;
        try {
            // 调用 OSS 合并所有分片为完整对象
            objectStorageService.completeMultipartUpload(
                    upload.objectKey(),
                    upload.storageUploadId(),
                    parts.stream()
                            .map(part -> new CompletedUploadPart(part.partNumber(), part.etag()))
                            .toList()
            );
            // HeadObject 获取实际对象大小和媒体类型，与客户端声明对账
            metadata = objectStorageService.headObject(upload.objectKey());
        } catch (MediaStorageException completeException) {
            // Complete 请求可能在服务端成功、响应却在网络中丢失；
            // 先尝试 HeadObject 确认对象是否已存在，再决定是否报错
            metadata = tryReadObjectMetadata(upload);
            if (metadata == null) {
                // 两个外部调用同时失败时无法判断对象是否已完成，保持 VERIFYING 供后续请求恢复确认
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "对象存储暂时不可用，上传完成状态尚未确认");
            }
        }

        // 校验实际对象大小和媒体类型是否与预期一致
        if (!isExpectedObject(upload, metadata)) {
            // 校验失败：删除已合并的对象，标记失败，要求重新上传
            safeDelete(upload.objectKey());
            markFailed(upload, "上传完成后的对象大小或媒体类型校验失败");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "上传对象校验失败，请重新上传视频");
        }

        // 所有校验通过：标记上传会话 COMPLETED + 成片 UPLOADED
        persistenceService.markUploadCompleted(
                upload,
                metadata.contentType(),
                metadata.contentLength(),
                LocalDateTime.now()
        );
        return toDraftVideoResponse(requireDraft(ownerId, taskId));
    }

    /**
     * 取消上传：标记上传会话 ABORTED、成片 UPLOAD_ABORTED、清理已登记分片记录。
     * 同时调用 OSS AbortMultipartUpload 释放未合并的分片（避免存储费用）。
     * 已完成或正在确认的会话不能取消。
     */
    public void abortUpload(String ownerId, String taskId, String uploadSessionId) {
        MediaUploadRecord upload = requireUpload(ownerId, taskId, uploadSessionId);
        // 已取消则幂等清理 OSS 资源即可
        if (MediaUploadStatus.ABORTED.name().equals(upload.status())) {
            abortStorageUpload(upload);
            return;
        }
        // 已过期或已被替代的会话，OSS 资源仍需清理
        if (MediaUploadStatus.EXPIRED.name().equals(upload.status())
                || MediaUploadStatus.SUPERSEDED.name().equals(upload.status())) {
            abortStorageUpload(upload);
            return;
        }
        // 已完成或正在确认的不能取消
        if (MediaUploadStatus.COMPLETED.name().equals(upload.status())
                || MediaUploadStatus.VERIFYING.name().equals(upload.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前成片正在确认或已经完成，不能取消上传");
        }
        // 标记 ABORTED + 清理分片记录
        if (!persistenceService.markUploadAborted(upload)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前上传状态已经变化，请刷新后重试");
        }
        abortStorageUpload(upload); // 调用 OSS 释放未合并分片
    }

    // ========== 私有辅助方法 ==========

    /**
     * 校验媒体能力和对象存储配置是否就绪。
     * 每个公开方法入口都调用此方法，避免外部调用时才发现缺配置。
     */
    private void ensureConfigurationReady() {
        try {
            mediaProperties.validateEnabledConfiguration(); // 校验媒体能力配置完整性
            storageProperties.validateConfigured();         // 校验对象存储配置完整性（AccessKey 非空等）
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    /**
     * 按幂等键查找已有上传会话。
     * 若找到但文件指纹不匹配，说明同一幂等键被用于不同视频，拒绝复用。
     * 若找到且会话已终止（ABORTED/EXPIRED/FAILED/SUPERSEDED），拒绝复用并要求新建。
     */
    private OptionalUpload findIdempotentUpload(String taskId,
                                                String ownerId,
                                                String idempotencyKey,
                                                String fingerprint) {
        MediaUploadRecord existing = mediaUploadMapper
                .findUploadByIdempotency(taskId, ownerId, idempotencyKey)
                .orElse(null);
        if (existing == null) {
            return OptionalUpload.empty(); // 无现存会话
        }
        // 文件指纹不匹配：同一幂等键但不同文件，拒绝以免误用
        if (!existing.fileFingerprint().equals(fingerprint)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一幂等键已经用于另一个视频文件");
        }
        expireIfNecessary(existing); // 先检查是否已过期
        // 已终止的会话不能继续使用
        if (MediaUploadStatus.ABORTED.name().equals(existing.status())
                || MediaUploadStatus.EXPIRED.name().equals(existing.status())
                || MediaUploadStatus.FAILED.name().equals(existing.status())
                || MediaUploadStatus.SUPERSEDED.name().equals(existing.status())) {
            throw new ResponseStatusException(HttpStatus.GONE, "原上传会话已经结束，请创建新的上传会话");
        }
        return OptionalUpload.of(existing); // 返回可复用的现存会话
    }

    /**
     * 根据 taskId、ownerId、uploadSessionId 三重校验查找上传会话。
     * 找不到则 404，确保只能操作自己的会话。
     */
    private MediaUploadRecord requireUpload(String ownerId, String taskId, String uploadSessionId) {
        return mediaUploadMapper.findUpload(taskId.trim(), ownerId, uploadSessionId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "媒体上传会话不存在"));
    }

    /**
     * 根据 taskId、ownerId 查找成片记录。
     */
    private DraftVideoRecord requireDraft(String ownerId, String taskId) {
        return mediaUploadMapper.findDraftVideo(taskId.trim(), ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "成片记录不存在"));
    }

    /**
     * 判断成片状态是否允许复用创建新上传。
     * 只有 UPLOAD_FAILED 和 UPLOAD_ABORTED 表示上一次尝试已结束，可以重新上传。
     */
    private boolean canReuseDraft(String status) {
        return DraftVideoStatus.UPLOAD_FAILED.name().equals(status)
                || DraftVideoStatus.UPLOAD_ABORTED.name().equals(status);
    }

    /**
     * 只允许 CREATED 和 UPLOADING 状态的分片操作（签名、登记）。
     */
    private void ensureStatusAllowsUpload(String status) {
        if (!MediaUploadStatus.CREATED.name().equals(status)
                && !MediaUploadStatus.UPLOADING.name().equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前上传状态不允许继续上传分片");
        }
    }

    /**
     * 只允许 UPLOADING 状态完成上传。
     */
    private void ensureStatusAllowsComplete(String status) {
        if (!MediaUploadStatus.UPLOADING.name().equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前上传状态不允许完成上传");
        }
    }

    /**
     * 校验单个已完成分片：序号不越界、大小与预期一致。
     * 最后一个分片允许小于 partSize，其余分片必须等于 partSize。
     */
    private void validatePart(MediaUploadRecord upload, CompletedMediaUploadPartRequest part) {
        if (part.partNumber() > upload.totalParts()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分片序号超过当前文件的总分片数");
        }
        // 计算该分片的预期大小：最后一片 = 总大小 - (总分片数-1) * 分片大小，其余 = 分片大小
        long expectedPartSize = part.partNumber() == upload.totalParts()
                ? upload.expectedSize() - (long) upload.partSize() * (upload.totalParts() - 1L)
                : upload.partSize();
        if (part.partSize() != expectedPartSize) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分片大小与上传会话不匹配");
        }
    }

    /**
     * 完成前校验分片集合：数量一致、序号连续、总大小匹配。
     */
    private void validateCompletePartSet(MediaUploadRecord upload, List<MediaUploadPartRecord> parts) {
        if (parts.size() != upload.totalParts()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仍有视频分片未上传完成");
        }
        long totalSize = 0L;
        for (int index = 0; index < parts.size(); index++) {
            MediaUploadPartRecord part = parts.get(index);
            int expectedPartNumber = index + 1;
            if (part.partNumber() != expectedPartNumber) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "已完成分片序号不连续");
            }
            totalSize += part.partSize();
        }
        if (totalSize != upload.expectedSize()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已完成分片总大小与原文件不一致");
        }
    }

    /**
     * 检查上传会话是否已过期，若过期则标记 EXPIRED 并抛出 GONE。
     * 只在非终态状态下检查，已完成的会话无需检查过期。
     */
    private void expireIfNecessary(MediaUploadRecord upload) {
        // 无过期时间 或 未过期 或 已是终态，无需处理
        if (upload.expiresAt() == null
                || !LocalDateTime.now().isAfter(upload.expiresAt())
                || isTerminalStatus(upload.status())) {
            return;
        }
        // 标记为 EXPIRED 并清理 OSS 资源
        if (!persistenceService.markUploadExpired(upload)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "上传会话状态已经变化，请刷新后重试");
        }
        safeAbort(upload.objectKey(), upload.storageUploadId());
        throw new ResponseStatusException(HttpStatus.GONE, "媒体上传会话已过期，请重新创建");
    }

    /**
     * 判断是否为终态（不会再发生变化的状态）。
     */
    private boolean isTerminalStatus(String status) {
        return MediaUploadStatus.COMPLETED.name().equals(status)
                || MediaUploadStatus.ABORTED.name().equals(status)
                || MediaUploadStatus.EXPIRED.name().equals(status)
                || MediaUploadStatus.FAILED.name().equals(status)
                || MediaUploadStatus.SUPERSEDED.name().equals(status);
    }

    /**
     * 恢复 VERIFYING 状态的上传会话。
     * <p>
     * VERIFYING 是 Complete 请求发送后、数据库更新前的中间态。
     * 恢复策略：
     * 1. HeadObject 成功 → 对象已存在，直接标记 COMPLETED
     * 2. HeadObject 失败且超时 → 将状态回退到 FAILED，允许客户端重新确认
     * 3. HeadObject 失败但未超时 → 返回 null，要求客户端稍后重试
     *
     * @return 恢复成功则返回 DraftVideoResponse，仍需等待则返回 null
     */
    private DraftVideoResponse recoverVerifyingUpload(MediaUploadRecord upload) {
        StoredObjectMetadata metadata = tryReadObjectMetadata(upload);
        if (metadata != null) {
            // HeadObject 成功：对象已存在，校验后直接标记完成
            if (!isExpectedObject(upload, metadata)) {
                safeDelete(upload.objectKey());
                markFailed(upload, "上传完成后的对象大小或媒体类型校验失败");
                throw new ResponseStatusException(HttpStatus.CONFLICT, "上传对象校验失败，请重新上传视频");
            }
            persistenceService.markUploadCompleted(
                    upload,
                    metadata.contentType(),
                    metadata.contentLength(),
                    LocalDateTime.now()
            );
            return toDraftVideoResponse(requireDraft(upload.ownerId(), upload.taskId()));
        }
        // HeadObject 失败：尝试将 VERIFYING 状态回退到 FAILED（需满足超时条件）
        if (mediaUploadMapper.reopenStaleVerifyingUpload(
                upload.taskId(),
                upload.ownerId(),
                upload.uploadSessionId(),
                LocalDateTime.now().minus(VERIFYING_RECOVERY_DELAY) // 仅超时的记录可回退
        ) != 1) {
            // 未超时：可能 OSS 正在合并中，要求客户端稍后重试
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成片正在由服务端确认，请稍后重试");
        }
        return null; // 状态已回退到 FAILED，调用方应重新发起完成请求
    }

    /**
     * 调用 OSS AbortMultipartUpload，释放未合并的分片。
     * 失败时抛出异常，因为 OSS 会持续对未完成的分片计费。
     */
    private void abortStorageUpload(MediaUploadRecord upload) {
        try {
            objectStorageService.abortMultipartUpload(upload.objectKey(), upload.storageUploadId());
        } catch (MediaStorageException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "对象存储暂时不可用，取消状态已保存，请稍后重试清理");
        }
    }

    /**
     * 尝试 HeadObject 获取对象元数据，失败时返回 null。
     * 用于 VERIFYING 恢复和 Complete 容错场景。
     */
    private StoredObjectMetadata tryReadObjectMetadata(MediaUploadRecord upload) {
        try {
            return objectStorageService.headObject(upload.objectKey());
        } catch (MediaStorageException ignored) {
            return null; // HeadObject 失败不抛异常，由调用方决定下一步
        }
    }

    /**
     * 校验实际对象是否与预期一致：大小相等、媒体类型为 video/mp4。
     */
    private boolean isExpectedObject(MediaUploadRecord upload, StoredObjectMetadata metadata) {
        return metadata.contentLength() == upload.expectedSize()
                && metadata.contentType() != null
                && metadata.contentType().toLowerCase(Locale.ROOT).startsWith("video/mp4");
    }

    /**
     * 标记上传和成片失败，失败原因截断到数据库列长度上限。
     */
    private void markFailed(MediaUploadRecord upload, String message) {
        persistenceService.markUploadFailed(upload, abbreviate(message, MAX_FAILURE_MESSAGE_LENGTH));
    }

    /**
     * 安全取消 OSS Multipart Upload，忽略所有异常。
     * 用于非关键路径（清理旧尝试、异常回滚），失败由 Bucket 生命周期策略兜底。
     */
    private void safeAbort(String objectKey, String storageUploadId) {
        try {
            objectStorageService.abortMultipartUpload(objectKey, storageUploadId);
        } catch (MediaStorageException ignored) {
            // 原业务异常优先返回；遗留 Multipart Upload 由 Bucket 生命周期规则兜底清理
        }
    }

    /**
     * 安全删除 OSS 对象，忽略所有异常。
     * 用于非关键路径的清理，失败不影响业务正确性。
     */
    private void safeDelete(String objectKey) {
        try {
            objectStorageService.deleteObject(objectKey);
        } catch (MediaStorageException ignored) {
            // 删除失败不能覆盖原业务事实；对象保留策略和后续清理能力负责最终收敛
        }
    }

    /**
     * 规范化文件名：去除路径分隔符，只保留文件名部分。
     * P0 只支持 MP4 格式。
     */
    private String normalizeFileName(String fileName) {
        // 统一反斜杠为正斜杠，然后取最后一段作为纯文件名
        String normalized = fileName.trim().replace('\\', '/');
        int lastSeparator = normalized.lastIndexOf('/');
        if (lastSeparator >= 0) {
            normalized = normalized.substring(lastSeparator + 1); // 去掉路径前缀
        }
        if (normalized.isBlank() || !normalized.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "P0 只支持 MP4 视频文件");
        }
        return normalized;
    }

    /**
     * 构造 OSS 对象键。
     * 格式：users/{ownerId}/tasks/{taskId}/versions/{versionId}/attempts/{uploadSessionId}/original/source.mp4
     * <p>
     * 每次上传尝试有独立的 attempt 目录，新旧尝试不会互相覆盖。
     * 对象键完全由后端生成，前端不得传入或猜测。
     */
    private String buildObjectKey(String ownerId, String taskId, String versionId, String uploadSessionId) {
        return "users/" + ownerId
                + "/tasks/" + taskId
                + "/versions/" + versionId
                + "/attempts/" + uploadSessionId
                + "/original/source.mp4";
    }

    /**
     * 计算文件指纹：文件名 + 大小 + 最后修改时间的 SHA-256 十六进制摘要。
     * 用于幂等键校验和续传对账：同一文件重复上传时指纹相同，不同文件必须不同。
     */
    private String fileFingerprint(String fileName, long fileSize, Long lastModified) {
        String source = fileName + "\n" + fileSize + "\n" + (lastModified == null ? 0L : lastModified);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    /**
     * 截断字符串到指定长度，用于失败原因摘要不超过数据库列限制。
     */
    private String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * 将数据库上传记录转换为 API 响应。
     * 额外查询已登记分片数，供前端展示上传进度。
     */
    private MediaUploadResponse toUploadResponse(MediaUploadRecord upload) {
        int completedPartCount = mediaUploadMapper.listParts(upload.uploadSessionId()).size();
        return new MediaUploadResponse(
                upload.uploadSessionId(),
                upload.versionId(),
                upload.taskId(),
                upload.status(),
                upload.expectedSize(),
                upload.fileFingerprint(),
                upload.partSize(),
                upload.totalParts(),
                completedPartCount,  // 已登记分片数，前端据此计算上传进度百分比
                upload.expiresAt(),
                upload.completedAt(),
                upload.failureMessage()
        );
    }

    /**
     * 将数据库分片记录转换为 API 响应。
     */
    private MediaUploadPartResponse toPartResponse(MediaUploadPartRecord part) {
        return new MediaUploadPartResponse(
                part.partNumber(),
                part.etag(),
                part.partSize(),
                part.completedAt()
        );
    }

    /**
     * 将数据库成片记录转换为 API 响应。
     */
    private DraftVideoResponse toDraftVideoResponse(DraftVideoRecord draft) {
        return new DraftVideoResponse(
                draft.versionId(),
                draft.taskId(),
                draft.versionNo(),
                draft.versionName(),
                draft.originalFileName(),
                draft.contentType(),
                draft.fileSize(),
                draft.status(),
                draft.createTime(),
                draft.updateTime()
        );
    }

    /**
     * 可选上传会话的内部封装。
     * 用于幂等查找的返回值，避免用 null 表示"未找到"。
     */
    private record OptionalUpload(boolean found, MediaUploadRecord upload) {

        private static OptionalUpload empty() {
            return new OptionalUpload(false, null);
        }

        private static OptionalUpload of(MediaUploadRecord upload) {
            return new OptionalUpload(true, upload);
        }
    }
}
