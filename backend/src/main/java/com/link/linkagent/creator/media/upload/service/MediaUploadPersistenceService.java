package com.link.linkagent.creator.media.upload.service;

import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.media.upload.model.MediaUploadPartRecord;
import com.link.linkagent.creator.media.upload.model.MediaUploadRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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

    /** 新文件只增加版本；任务锁同时保护空工作区的首次分配和并发幂等请求。 */
    @Transactional
    public MediaUploadRecord saveCreatedUpload(DraftVideoRecord draftVideo,
                                                MediaUploadRecord upload,
                                                long expectedWorkspaceRevision) {
        lockTask(upload.taskId(), upload.ownerId());
        var existing = mediaUploadMapper.findUploadByIdempotency(upload.taskId(), upload.ownerId(), upload.idempotencyKey());
        if (existing.isPresent()) {
            if (!existing.get().fileFingerprint().equals(upload.fileFingerprint())
                    || !existing.get().contentType().equals(upload.contentType())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "同一幂等键已经用于另一个视频文件");
            }
            return existing.get();
        }
        ensureNoVideoBinding(upload.taskId(), upload.ownerId());
        if (mediaUploadMapper.countActiveCreationRuns(upload.taskId(), upload.ownerId()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "制作运行尚未结束，请完成或取消后再上传覆盖视频");
        }
        if (mediaUploadMapper.findCurrentUpload(upload.taskId(), upload.ownerId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前任务仍有上传未结束，请先完成或取消原上传");
        }
        long currentRevision = mediaUploadMapper.findMediaWorkspace(upload.taskId(), upload.ownerId())
                .map(workspace -> workspace.workspaceRevision()).orElse(-1L);
        // OSS 创建期间可能已重新定位；即使新蓝图已生成，也不能把旧请求采纳为新方向的成片。
        if (currentRevision != expectedWorkspaceRevision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "视频制作上下文已经变化，请刷新后重新上传");
        }
        mediaUploadMapper.adoptMediaWorkspace(upload.taskId(), upload.ownerId());
        var workspace = mediaUploadMapper.findMediaWorkspace(upload.taskId(), upload.ownerId()).orElseThrow();
        if (workspace.nextVersionNo() == Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成片版本序号已达到上限");
        }
        if (mediaUploadMapper.advanceMediaVersion(upload.taskId(), upload.ownerId(), workspace.nextVersionNo()) != 1
                || mediaUploadMapper.insertDraftVideo(draftVideo.withVersionNo(workspace.nextVersionNo())) != 1) {
            throw new IllegalStateException("成片版本分配失败，请刷新后重试");
        }
        if (mediaUploadMapper.insertUpload(upload) != 1) {
            throw new IllegalStateException("媒体上传会话保存失败");
        }
        if (mediaUploadMapper.captureUploadVersionContext(upload.taskId(), upload.ownerId(), upload.versionId()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "发布确认或制作蓝图已经变化，请确认后重新上传");
        }
        return upload;
    }

    private void lockTask(String taskId, String ownerId) {
        mediaUploadMapper.lockMediaTask(taskId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在"));
    }

    private void ensureNoVideoBinding(String taskId, String ownerId) {
        if (mediaUploadMapper.countPublishingFacts(taskId, ownerId) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前任务已有发布标记或 BV 关联，请新建任务上传修订视频");
        }
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
        lockTask(upload.taskId(), upload.ownerId());
        var latest = mediaUploadMapper.findUpload(upload.taskId(), upload.ownerId(), upload.uploadSessionId())
                .orElseThrow(() -> new IllegalStateException("媒体上传会话不存在"));
        // 并发完成请求只能登记一次；旧请求重放不能再次把指针切回旧版。
        if ("COMPLETED".equals(latest.status())) {
            return;
        }
        ensureNoVideoBinding(upload.taskId(), upload.ownerId());
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
        mediaUploadMapper.adoptMediaWorkspace(upload.taskId(), upload.ownerId());
        var workspace = mediaUploadMapper.findMediaWorkspace(upload.taskId(), upload.ownerId()).orElseThrow();
        if (mediaUploadMapper.activateUploadedVersion(upload.taskId(), upload.ownerId(), upload.versionId(),
                workspace.workspaceRevision()) != 1) {
            throw new IllegalStateException("当前成片已变化，请刷新后重试");
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
        if (mediaUploadMapper.updateDraftVideoStatus(
                upload.taskId(),
                upload.ownerId(),
                upload.versionId(),
                upload.objectKey(),
                "UPLOAD_ABORTED",
                upload.contentType(),
                upload.expectedSize()
        ) != 1) {
            throw new IllegalStateException("成片取消状态保存失败");
        }
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
        if (mediaUploadMapper.updateDraftVideoStatus(
                upload.taskId(),
                upload.ownerId(),
                upload.versionId(),
                upload.objectKey(),
                "UPLOAD_FAILED",
                upload.contentType(),
                upload.expectedSize()
        ) != 1) {
            throw new IllegalStateException("成片过期状态保存失败");
        }
        return true;
    }
}
