package com.link.linkagent.creator.media.upload.service;

import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.media.upload.model.MediaUploadPartRecord;
import com.link.linkagent.creator.media.upload.model.MediaUploadRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体上传的数据库原子写入边界。
 * <p>
 * 对象存储不参与 MySQL 事务（OSS 不支持 XA），因此外部调用由 {@link MediaUploadService}
 * 在事务外执行 OSS 操作，本类只保证成片、上传会话和分片事实的本地写入不会出现半成功。
 * <p>
 * 所有方法都使用 {@link Transactional} 注解，确保本方法内的多个 Mapper 调用在同一事务中：
 * 任一调用失败则全部回滚，数据库状态始终保持一致。
 */
@Service
public class MediaUploadPersistenceService {

    private final MediaUploadMapper mediaUploadMapper;

    public MediaUploadPersistenceService(MediaUploadMapper mediaUploadMapper) {
        this.mediaUploadMapper = mediaUploadMapper;
    }

    /**
     * 在事务内原子完成：成片记录创建/重置 + 上传会话插入 + 旧会话替换标记。
     * <p>
     * 复用已有成片时（createDraftVideo=false），必须先对旧上传会话加行级锁（FOR UPDATE），
     * 防止并发创建两个上传会话同时争用同一个成片版本。
     *
     * @param draftVideo        成片记录（新建时 id 为 null）
     * @param createDraftVideo  true=新建成片记录，false=重置已有成片记录
     * @param previousObjectKey 旧尝试的对象键（仅复用成片时需要），用于行级锁定位和替换校验
     * @param upload            新上传会话记录
     * @return 被替换的旧上传会话（若存在），供调用方在事务外清理 OSS 资源
     */
    @Transactional
    public MediaUploadRecord saveCreatedUpload(DraftVideoRecord draftVideo,
                                                boolean createDraftVideo,
                                                String previousObjectKey,
                                                MediaUploadRecord upload) {
        MediaUploadRecord replacedUpload = null;
        int draftRows; // 受影响的成片行数，必须为 1 才算成功

        if (createDraftVideo) {
            // 场景 1：该任务首次创建成片，直接 INSERT
            draftRows = mediaUploadMapper.insertDraftVideo(draftVideo);
        } else {
            // 场景 2：已有成片记录需要重置（上次尝试失败或取消）
            // 先对旧上传会话加行级锁，防止并发覆盖
            replacedUpload = mediaUploadMapper.lockUploadByObjectKey(
                            draftVideo.versionId(),
                            draftVideo.taskId(),
                            draftVideo.ownerId(),
                            previousObjectKey  // 用旧对象键精确定位，确保锁的是正确的行
                    )
                    .orElseThrow(() -> new IllegalStateException("原上传会话不存在，不能创建新的上传会话"));

            // 根据旧会话状态决定是否允许替换
            if ("FAILED".equals(replacedUpload.status())) {
                // 失败会话：先标记为 SUPERSEDED（已被替代），再创建新会话
                if (mediaUploadMapper.supersedeFailedUpload(
                        replacedUpload.taskId(),
                        replacedUpload.ownerId(),
                        replacedUpload.uploadSessionId()
                ) != 1) {
                    throw new IllegalStateException("原上传会话状态已经变化，不能创建新的上传会话");
                }
            } else if (!"ABORTED".equals(replacedUpload.status())
                    && !"EXPIRED".equals(replacedUpload.status())) {
                // VERIFYING 可能正在完成 OSS 对象，必须拒绝替换，避免两个成片同时争用当前版本
                throw new IllegalStateException("原上传会话仍在处理，不能创建新的上传会话");
            }

            // 更新成片记录：对象键、版本名等字段替换为新值，状态重置为 UPLOADING
            draftRows = mediaUploadMapper.resetDraftVideoForUpload(draftVideo, previousObjectKey);
        }

        // 校验成片操作受影响行数：必须恰好为 1
        if (draftRows != 1) {
            throw new IllegalStateException("成片状态已经变化，不能创建新的上传会话");
        }

        // 插入新的上传会话记录
        if (mediaUploadMapper.insertUpload(upload) != 1) {
            throw new IllegalStateException("媒体上传会话保存失败");
        }

        return replacedUpload; // 返回被替换的旧会话，供调用方清理 OSS 资源
    }

    /**
     * 在事务内原子完成：批量登记分片 + 更新上传会话状态为 UPLOADING。
     * <p>
     * 分片使用 ON DUPLICATE KEY UPDATE 实现幂等写入，同一分片可重复登记。
     * 状态更新使用 CAS（Compare-And-Swap）：只有 CREATED/UPLOADING 状态才允许更新，
     * 防止在已完成的会话上继续登记分片。
     */
    @Transactional
    public void saveCompletedParts(String taskId,
                                   String ownerId,
                                   String uploadSessionId,
                                   List<MediaUploadPartRecord> parts) {
        // 逐个写入分片记录（ON DUPLICATE KEY UPDATE 保证幂等）
        for (MediaUploadPartRecord part : parts) {
            mediaUploadMapper.upsertPart(part);
        }
        // CAS 更新上传会话状态：CREATED → UPLOADING 或保持 UPLOADING
        int updatedRows = mediaUploadMapper.markUploadUploading(
                taskId,
                ownerId,
                uploadSessionId
        );
        if (updatedRows != 1) {
            // 可能已经被取消、完成或过期
            throw new IllegalStateException("当前上传状态已经结束，不能继续登记分片");
        }
    }

    /**
     * 在事务内原子完成：上传会话 COMPLETED + 成片 UPLOADED。
     * 两步必须同时成功，否则全部回滚。
     */
    @Transactional
    public void markUploadCompleted(MediaUploadRecord upload,
                                    String contentType,
                                    long fileSize,
                                    LocalDateTime completedAt) {
        // 步 1：标记上传会话为 COMPLETED（CAS：仅 VERIFYING → COMPLETED）
        if (mediaUploadMapper.completeUpload(
                upload.taskId(),
                upload.ownerId(),
                upload.uploadSessionId(),
                completedAt
        ) != 1) {
            throw new IllegalStateException("媒体上传会话完成状态保存失败");
        }
        // 步 2：标记成片为 UPLOADED，同时更新实际文件大小和媒体类型（以 HeadObject 结果为准）
        if (mediaUploadMapper.updateDraftVideoStatus(
                upload.taskId(),
                upload.ownerId(),
                upload.versionId(),
                upload.objectKey(),
                "UPLOADED",            // P0 最终状态
                contentType,           // 以对象存储实际类型为准
                fileSize               // 以对象存储实际大小为准
        ) != 1) {
            throw new IllegalStateException("成片上传完成状态保存失败");
        }
    }

    /**
     * 在事务内原子完成：上传会话 FAILED + 成片 UPLOAD_FAILED。
     */
    @Transactional
    public void markUploadFailed(MediaUploadRecord upload, String failureMessage) {
        // 步 1：标记上传会话为 FAILED（CAS：仅 VERIFYING → FAILED）
        if (mediaUploadMapper.failUpload(
                upload.taskId(),
                upload.ownerId(),
                upload.uploadSessionId(),
                failureMessage
        ) != 1) {
            throw new IllegalStateException("媒体上传会话失败状态保存失败");
        }
        // 步 2：标记成片为 UPLOAD_FAILED
        if (mediaUploadMapper.updateDraftVideoStatus(
                upload.taskId(),
                upload.ownerId(),
                upload.versionId(),
                upload.objectKey(),
                "UPLOAD_FAILED",
                upload.contentType(),
                upload.expectedSize()
        ) != 1) {
            throw new IllegalStateException("成片上传失败状态保存失败");
        }
    }

    /**
     * 在事务内原子完成：上传会话 ABORTED + 成片 UPLOAD_ABORTED + 清理分片记录。
     * <p>
     * 与 fail 不同：abort 会清理已登记的分片记录（DELETE），因为取消后分片数据无保留价值。
     * 返回 false 表示 CAS 失败（状态已变化），调用方不应继续执行 OSS 清理。
     */
    @Transactional
    public boolean markUploadAborted(MediaUploadRecord upload) {
        // 步 1：CAS 标记上传会话为 ABORTED
        if (mediaUploadMapper.abortUpload(
                upload.taskId(),
                upload.ownerId(),
                upload.uploadSessionId()
        ) != 1) {
            return false; // CAS 失败，状态已被其他操作修改
        }
        // 步 2：标记成片为 UPLOAD_ABORTED
        mediaUploadMapper.updateDraftVideoStatus(
                upload.taskId(),
                upload.ownerId(),
                upload.versionId(),
                upload.objectKey(),
                "UPLOAD_ABORTED",
                upload.contentType(),
                upload.expectedSize()
        );
        // 步 3：清理已登记分片记录（取消后无保留价值，释放存储空间）
        mediaUploadMapper.deleteParts(upload.uploadSessionId());
        return true;
    }

    /**
     * 在事务内原子完成：上传会话 EXPIRED + 成片 UPLOAD_FAILED。
     * <p>
     * 过期视为一种特殊的失败，不清理分片记录（保留事实用于排查）。
     * 返回 false 表示 CAS 失败（状态已变化或尚未过期）。
     */
    @Transactional
    public boolean markUploadExpired(MediaUploadRecord upload) {
        // 步 1：CAS 标记上传会话为 EXPIRED（条件：状态为可过期状态 + expires_at < NOW()）
        if (mediaUploadMapper.expireUpload(
                upload.taskId(),
                upload.ownerId(),
                upload.uploadSessionId()
        ) != 1) {
            return false; // CAS 失败，可能尚未过期或状态已变化
        }
        // 步 2：标记成片为 UPLOAD_FAILED
        mediaUploadMapper.updateDraftVideoStatus(
                upload.taskId(),
                upload.ownerId(),
                upload.versionId(),
                upload.objectKey(),
                "UPLOAD_FAILED",
                upload.contentType(),
                upload.expectedSize()
        );
        return true;
    }
}
