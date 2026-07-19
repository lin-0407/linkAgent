package com.link.linkagent.creator.media.probe.service;

import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.probe.model.MediaProbeResult;
import com.link.linkagent.creator.media.storage.MediaStorageException;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.storage.PresignedObjectRead;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.media.upload.model.DraftVideoResponse;
import com.link.linkagent.creator.media.upload.model.DraftVideoStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * 成片媒体探测应用服务。
 * <p>
 * 该服务只处理确定性文件元信息，不做模型分析，避免把上传成功误认为已经完成试映。
 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class DraftVideoProbeService {

    private final CreatorMediaProperties mediaProperties;
    private final ObjectStorageService objectStorageService;
    private final FfprobeMediaProbeService ffprobeMediaProbeService;
    private final MediaUploadMapper mediaUploadMapper;
    private final DraftVideoProbeRecoveryService probeRecoveryService;

    public DraftVideoProbeService(CreatorMediaProperties mediaProperties,
                                  ObjectStorageService objectStorageService,
                                  FfprobeMediaProbeService ffprobeMediaProbeService,
                                  MediaUploadMapper mediaUploadMapper,
                                  DraftVideoProbeRecoveryService probeRecoveryService) {
        this.mediaProperties = mediaProperties;
        this.objectStorageService = objectStorageService;
        this.ffprobeMediaProbeService = ffprobeMediaProbeService;
        this.mediaUploadMapper = mediaUploadMapper;
        this.probeRecoveryService = probeRecoveryService;
    }

    /**
     * 对已上传成片执行 ffprobe 探测，并写回媒体元信息。
     *
     * @param ownerId   单人工作台固定归属
     * @param taskId    创作任务 ID
     * @param versionId 成片版本 ID
     * @return 更新后的成片事实
     */
    public DraftVideoResponse probeDraftVideo(String ownerId, String taskId, String versionId) {
        try {
            mediaProperties.validateEnabledConfiguration();
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
        DraftVideoRecord draft = requireProbeableDraft(ownerId, taskId, versionId);
        String probeAttemptId = claimProbe(draft);
        MediaProbeResult result;
        try {
            PresignedObjectRead signedRead = objectStorageService.presignGetObject(
                    draft.bucketName(),
                    draft.objectKey(),
                    mediaProperties.getProcessing().getProviderReadTtl()
            );
            result = ffprobeMediaProbeService.probe(signedRead.url());
        } catch (MediaStorageException exception) {
            markProbeFailed(draft, probeAttemptId, null);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "对象存储暂时不可用，无法生成媒体读取签名");
        } catch (MediaProbeException exception) {
            markProbeFailed(draft, probeAttemptId, null);
            throw new ResponseStatusException(exception.getStatus(), exception.getMessage());
        } catch (RuntimeException exception) {
            // 已领取为 PROBING 的成片不能因未预期运行时异常永久卡住，先收敛为可重试状态。
            markProbeFailed(draft, probeAttemptId, null);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "媒体探测执行失败，请稍后重试");
        }

        if (result.durationMs() > mediaProperties.getMaxDurationMs()) {
            markProbeFailed(draft, probeAttemptId, result);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "视频时长超过30分钟，暂不能进入发布前试映");
        }

        updateProbeResult(draft, probeAttemptId, DraftVideoStatus.READY_FOR_REVIEW, result);
        DraftVideoRecord updated = mediaUploadMapper.findDraftVideoByVersion(draft.taskId(), draft.ownerId(), draft.versionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "媒体探测后读取成片失败"));
        return toResponse(updated);
    }

    /**
     * 查询指定成片的已持久化事实。
     * <p>
     * 页面刷新后不能依赖浏览器内存恢复探测结果，因此通过此接口读取 MySQL 中的版本状态和元信息。
     */
    public DraftVideoResponse getDraftVideo(String ownerId, String taskId, String versionId) {
        DraftVideoRecord draft = recoverStaleProbeIfNecessary(requireDraft(ownerId, taskId, versionId));
        return toResponse(draft);
    }

    /**
     * 查询任务当前唯一成片的持久化事实。
     * <p>
     * P0 固定一个任务只维护 V1，因此工作台不需要知道内部 versionId 也能恢复媒体门禁状态。
     */
    public DraftVideoResponse getCurrentDraftVideo(String ownerId, String taskId) {
        DraftVideoRecord draft = mediaUploadMapper.findDraftVideo(taskId.trim(), ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "当前任务尚未上传成片"));
        return toResponse(recoverStaleProbeIfNecessary(draft));
    }

    private DraftVideoRecord requireProbeableDraft(String ownerId, String taskId, String versionId) {
        DraftVideoRecord draft = recoverStaleProbeIfNecessary(requireDraft(ownerId, taskId, versionId));
        if (!DraftVideoStatus.UPLOADED.name().equals(draft.status())
                && !DraftVideoStatus.PROBE_FAILED.name().equals(draft.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前成片状态不能执行媒体探测");
        }
        return draft;
    }

    private void markProbeFailed(DraftVideoRecord draft, String probeAttemptId, MediaProbeResult result) {
        updateProbeResult(draft, probeAttemptId, DraftVideoStatus.PROBE_FAILED, result);
    }

    private String claimProbe(DraftVideoRecord draft) {
        String probeAttemptId = UUID.randomUUID().toString();
        if (mediaUploadMapper.claimDraftVideoProbe(
                draft.taskId(),
                draft.ownerId(),
                draft.versionId(),
                probeAttemptId
        ) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前成片正在探测或状态已变化，请刷新后重试");
        }
        return probeAttemptId;
    }

    private DraftVideoRecord recoverStaleProbeIfNecessary(DraftVideoRecord draft) {
        if (!probeRecoveryService.isStale(draft)) {
            return draft;
        }
        return probeRecoveryService.recover(draft);
    }

    private DraftVideoRecord requireDraft(String ownerId, String taskId, String versionId) {
        return mediaUploadMapper.findDraftVideoByVersion(taskId.trim(), ownerId, versionId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "成片记录不存在"));
    }

    private void updateProbeResult(DraftVideoRecord draft,
                                   String probeAttemptId,
                                   DraftVideoStatus status,
                                   MediaProbeResult result) {
        int updatedRows = mediaUploadMapper.updateDraftVideoProbeResult(
                draft.taskId(),
                draft.ownerId(),
                draft.versionId(),
                DraftVideoStatus.PROBING.name(),
                probeAttemptId,
                status.name(),
                result == null ? null : result.durationMs(),
                result == null ? null : result.width(),
                result == null ? null : result.height(),
                result == null ? null : result.frameRate(),
                result == null ? null : result.videoCodec(),
                result == null ? null : result.audioCodec(),
                result == null ? null : result.hasAudio()
        );
        if (updatedRows != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成片状态已变化，请刷新后重试");
        }
    }

    private DraftVideoResponse toResponse(DraftVideoRecord draft) {
        return new DraftVideoResponse(
                draft.versionId(),
                draft.taskId(),
                draft.versionNo(),
                draft.versionName(),
                draft.originalFileName(),
                draft.contentType(),
                draft.fileSize(),
                draft.durationMs(),
                draft.width(),
                draft.height(),
                draft.frameRate(),
                draft.videoCodec(),
                draft.audioCodec(),
                draft.hasAudio(),
                draft.status(),
                draft.createTime(),
                draft.updateTime()
        );
    }
}
