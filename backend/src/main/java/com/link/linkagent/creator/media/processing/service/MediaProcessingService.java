package com.link.linkagent.creator.media.processing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.processing.model.MediaProcessingAssetReadUrlResponse;
import com.link.linkagent.creator.media.processing.model.MediaProcessingAssetRecord;
import com.link.linkagent.creator.media.processing.model.MediaProcessingEstimate;
import com.link.linkagent.creator.media.processing.model.MediaProcessingJobRecord;
import com.link.linkagent.creator.media.processing.model.MediaProcessingJobResponse;
import com.link.linkagent.creator.media.processing.model.MediaProcessingOptionsRequest;
import com.link.linkagent.creator.media.processing.model.MediaProcessingStepRecord;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.storage.PresignedObjectRead;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.media.upload.model.DraftVideoStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * P0-2 媒体预处理应用服务。
 * 负责校验成片门禁、保存用户选项、返回可轮询快照和签发派生素材短时地址。
 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class MediaProcessingService {

    private static final String COST_NOTICE = "按当前配置估算，仅供选择处理档位，不代表供应商最终账单";

    private static final List<StepDefinition> STEPS = List.of(
            new StepDefinition("DOWNLOAD", "下载原片", 1),
            new StepDefinition("PREVIEW", "生成分析预览", 2),
            new StepDefinition("AUDIO", "提取音轨", 3),
            new StepDefinition("FRAMES", "提取关键画面", 4),
            new StepDefinition("SIGNALS", "检测画面与声音信号", 5),
            new StepDefinition("UPLOAD", "保存派生素材", 6)
    );

    private final CreatorMediaProperties mediaProperties;
    private final MediaProcessingCostEstimator costEstimator;
    private final MediaProcessingMapper processingMapper;
    private final MediaUploadMapper mediaUploadMapper;
    private final ObjectStorageService objectStorageService;
    private final ObjectMapper objectMapper;

    public MediaProcessingService(CreatorMediaProperties mediaProperties,
                                  MediaProcessingCostEstimator costEstimator,
                                  MediaProcessingMapper processingMapper,
                                  MediaUploadMapper mediaUploadMapper,
                                  ObjectStorageService objectStorageService,
                                  ObjectMapper objectMapper) {
        this.mediaProperties = mediaProperties;
        this.costEstimator = costEstimator;
        this.processingMapper = processingMapper;
        this.mediaUploadMapper = mediaUploadMapper;
        this.objectStorageService = objectStorageService;
        this.objectMapper = objectMapper;
    }

    public MediaProcessingEstimate estimate(String ownerId,
                                            String taskId,
                                            String versionId,
                                            MediaProcessingOptionsRequest options) {
        DraftVideoRecord draft = requireReadyDraft(ownerId, taskId, versionId);
        return costEstimator.estimate(
                draft.durationMs(),
                Boolean.TRUE.equals(draft.hasAudio()),
                options
        );
    }

    @Transactional
    public MediaProcessingJobResponse createJob(String ownerId,
                                                String taskId,
                                                String versionId,
                                                MediaProcessingOptionsRequest options) {
        validateEnabledConfiguration();
        processingMapper.lockDraftVersion(taskId, ownerId, versionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "成片记录不存在"));
        DraftVideoRecord draft = requireReadyDraft(ownerId, taskId, versionId);
        MediaProcessingJobRecord current = processingMapper.findCurrentJob(taskId, ownerId, versionId)
                .orElse(null);
        if (current != null && shouldReuse(current, options)) {
            return toResponse(current);
        }
        if (current != null && ("QUEUED".equals(current.status()) || "RUNNING".equals(current.status()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前成片已有预处理任务，请等待完成后再调整方案");
        }

        MediaProcessingEstimate estimate = costEstimator.estimate(
                draft.durationMs(),
                Boolean.TRUE.equals(draft.hasAudio()),
                options
        );
        String jobId = UUID.randomUUID().toString();
        MediaProcessingJobRecord record = new MediaProcessingJobRecord(
                null,
                jobId,
                draft.versionId(),
                draft.taskId(),
                draft.ownerId(),
                options.frameIntervalSeconds(),
                options.resolution().name(),
                options.resolution().getHeight(),
                options.modelPlan().name(),
                options.includeAsr(),
                estimate.pricingVersion(),
                estimate.estimatedFrameCount(),
                estimate.estimatedVisualInputTokens(),
                estimate.estimatedVisualOutputTokens(),
                estimate.estimatedAsrSeconds(),
                estimate.estimatedVisualCostUsd(),
                estimate.estimatedAsrCostUsd(),
                estimate.estimatedTotalCostUsd(),
                "QUEUED",
                "QUEUED",
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        if (processingMapper.insertJob(record) != 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "创建媒体预处理任务失败");
        }
        // 新预处理结果会替换旧素材基础，旧试映不得继续作为当前发布门禁依据。
        processingMapper.clearCurrentReview(taskId, ownerId, versionId);
        for (StepDefinition definition : STEPS) {
            MediaProcessingStepRecord step = new MediaProcessingStepRecord(
                    null,
                    UUID.randomUUID().toString(),
                    jobId,
                    definition.code(),
                    definition.name(),
                    definition.sequenceNo(),
                    "PENDING",
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
            if (processingMapper.insertStep(step) != 1) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "创建媒体预处理步骤失败");
            }
        }
        return getJob(ownerId, taskId, versionId, jobId);
    }

    public MediaProcessingJobResponse getCurrentJob(String ownerId, String taskId, String versionId) {
        requireDraft(ownerId, taskId, versionId);
        MediaProcessingJobRecord job = processingMapper.findCurrentJob(taskId, ownerId, versionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "当前成片尚无媒体预处理任务"));
        return toResponse(job);
    }

    @Transactional
    public MediaProcessingJobResponse retryJob(String ownerId,
                                               String taskId,
                                               String versionId,
                                               String jobId) {
        // 重试也锁定成片记录，避免用户删除媒体的同时把失败任务重新排队。
        processingMapper.lockDraftVersion(taskId, ownerId, versionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "成片记录不存在"));
        requireReadyDraft(ownerId, taskId, versionId);
        MediaProcessingJobRecord job = requireJob(ownerId, taskId, versionId, jobId);
        if (!"FAILED".equals(job.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有失败的媒体预处理任务可以重试");
        }
        if (processingMapper.retryJob(taskId, ownerId, versionId, jobId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "媒体预处理任务状态已变化，请刷新后重试");
        }
        for (StepDefinition definition : STEPS) {
            processingMapper.updateStep(jobId, definition.code(), "PENDING", 0, null, null);
        }
        return getJob(ownerId, taskId, versionId, jobId);
    }

    public MediaProcessingAssetReadUrlResponse createAssetReadUrl(String ownerId,
                                                                  String taskId,
                                                                  String versionId,
                                                                  String assetId) {
        requireDraft(ownerId, taskId, versionId);
        MediaProcessingAssetRecord asset = processingMapper.findAsset(taskId, ownerId, versionId, assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "派生素材不存在"));
        PresignedObjectRead read = objectStorageService.presignBrowserGetObject(
                asset.bucketName(),
                asset.objectKey(),
                mediaProperties.getUpload().getPresignTtl()
        );
        return new MediaProcessingAssetReadUrlResponse(asset.assetId(), read.url(), read.expiresAt());
    }

    private MediaProcessingJobResponse getJob(String ownerId,
                                              String taskId,
                                              String versionId,
                                              String jobId) {
        return toResponse(requireJob(ownerId, taskId, versionId, jobId));
    }

    private MediaProcessingJobRecord requireJob(String ownerId,
                                                String taskId,
                                                String versionId,
                                                String jobId) {
        return processingMapper.findJob(taskId, ownerId, versionId, jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "媒体预处理任务不存在"));
    }

    private DraftVideoRecord requireReadyDraft(String ownerId, String taskId, String versionId) {
        validateEnabledConfiguration();
        DraftVideoRecord draft = requireDraft(ownerId, taskId, versionId);
        if (draft.mediaDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "媒体文件已删除，不能重新生成预览和关键画面");
        }
        if (!DraftVideoStatus.READY_FOR_REVIEW.name().equals(draft.status())
                || draft.durationMs() == null
                || draft.width() == null
                || draft.height() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成片必须先通过媒体探测才能开始预处理");
        }
        return draft;
    }

    private DraftVideoRecord requireDraft(String ownerId, String taskId, String versionId) {
        return mediaUploadMapper.findDraftVideoByVersion(taskId.trim(), ownerId, versionId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "成片记录不存在"));
    }

    private void validateEnabledConfiguration() {
        try {
            mediaProperties.validateEnabledConfiguration();
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    private boolean shouldReuse(MediaProcessingJobRecord current, MediaProcessingOptionsRequest options) {
        if (!"COMPLETED".equals(current.status()) && !"FAILED".equals(current.status())) {
            return false;
        }
        return current.frameIntervalSeconds().equals(options.frameIntervalSeconds())
                && current.targetResolution().equals(options.resolution().name())
                && current.modelPlan().equals(options.modelPlan().name())
                && current.includeAsr().equals(options.includeAsr());
    }

    private MediaProcessingJobResponse toResponse(MediaProcessingJobRecord job) {
        List<MediaProcessingJobResponse.Step> steps = processingMapper.listSteps(job.jobId()).stream()
                .map(step -> new MediaProcessingJobResponse.Step(
                        step.stepCode(),
                        step.stepName(),
                        step.sequenceNo(),
                        step.status(),
                        step.progressPercent(),
                        step.outputSummary(),
                        step.failureMessage()
                ))
                .toList();
        List<MediaProcessingJobResponse.Asset> assets = processingMapper.listAssets(job.jobId()).stream()
                .map(asset -> new MediaProcessingJobResponse.Asset(
                        asset.assetId(),
                        asset.assetType(),
                        asset.contentType(),
                        asset.fileSize(),
                        asset.sequenceNo(),
                        asset.timestampMs(),
                        asset.width(),
                        asset.height(),
                        asset.durationMs()
                ))
                .toList();
        return new MediaProcessingJobResponse(
                job.jobId(),
                job.versionId(),
                job.taskId(),
                job.frameIntervalSeconds(),
                job.targetResolution(),
                job.modelPlan(),
                Boolean.TRUE.equals(job.includeAsr()),
                job.pricingVersion(),
                job.estimatedFrameCount(),
                job.estimatedVisualInputTokens(),
                job.estimatedVisualOutputTokens(),
                job.estimatedAsrSeconds(),
                job.estimatedVisualCostUsd(),
                job.estimatedAsrCostUsd(),
                job.estimatedTotalCostUsd(),
                job.status(),
                job.currentStep(),
                job.progressPercent(),
                job.attemptCount(),
                job.failureMessage(),
                parseSignalSummary(job.signalSummaryJson()),
                job.startedAt(),
                job.completedAt(),
                job.createTime(),
                job.updateTime(),
                steps,
                assets,
                COST_NOTICE
        );
    }

    private JsonNode parseSignalSummary(String json) {
        if (json == null || json.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            // 历史异常数据不应阻断任务查询，返回空摘要并保留原任务状态。
            return NullNode.getInstance();
        }
    }

    private record StepDefinition(String code, String name, int sequenceNo) {
    }
}
