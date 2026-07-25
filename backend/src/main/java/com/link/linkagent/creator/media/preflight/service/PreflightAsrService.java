package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.model.TimelineEvidenceRecord;
import com.link.linkagent.creator.media.preflight.provider.SpeechRecognitionProvider;
import com.link.linkagent.creator.media.processing.model.MediaProcessingAssetRecord;
import com.link.linkagent.creator.media.storage.ObjectStorageService;
import com.link.linkagent.creator.media.storage.PresignedObjectRead;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ASR 提交与结果持久化服务。
 * 已存在 providerTaskId 时只能返回原 ID，防止服务恢复或页面重试造成重复提交费用。
 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class PreflightAsrService {

    private final CreatorMediaProperties properties;
    private final PreflightReviewMapper mapper;
    private final ObjectStorageService storage;
    private final SpeechRecognitionProvider provider;
    private final ObjectMapper objectMapper;

    public PreflightAsrService(CreatorMediaProperties properties,
                               PreflightReviewMapper mapper,
                               ObjectStorageService storage,
                               SpeechRecognitionProvider provider,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.storage = storage;
        this.provider = provider;
        this.objectMapper = objectMapper;
    }

    public String ensureSubmitted(PreflightReviewRecord review,
                                  PreflightStepRecord step,
                                  MediaProcessingAssetRecord audioAsset) {
        if (step.providerTaskId() != null && !step.providerTaskId().isBlank()) {
            ensureCallLog(review, step, step.providerTaskId());
            return step.providerTaskId();
        }
        if (!"PENDING".equals(step.status())) {
            throw new AmbiguousSubmissionException("ASR 提交状态不确定，为避免重复计费已停止自动重试");
        }
        if (mapper.startStep(review.reviewId(), "TRANSCRIBE") != 1) {
            throw new IllegalStateException("ASR 步骤状态已变化");
        }
        PresignedObjectRead read = storage.presignAsrGetObject(
                audioAsset.bucketName(), audioAsset.objectKey(), properties.getProcessing().getProviderReadTtl());
        String providerTaskId = provider.submit(read.url());
        saveSubmission(review, step, providerTaskId);
        return providerTaskId;
    }

    private void saveSubmission(PreflightReviewRecord review,
                                PreflightStepRecord step,
                                String providerTaskId) {
        String outputRef = json(Map.of("submitted", true));
        if (mapper.saveProviderTaskId(review.reviewId(), providerTaskId, outputRef) != 1) {
            throw new AmbiguousSubmissionException("ASR 已提交但任务 ID 保存失败，为避免重复计费已停止自动重试");
        }
        ensureCallLog(review, step, providerTaskId);
    }

    private void ensureCallLog(PreflightReviewRecord review,
                               PreflightStepRecord step,
                               String providerTaskId) {
        int inserted = mapper.insertAsrCall(
                UUID.randomUUID().toString(), review.taskId(), review.versionId(), review.reviewId(),
                step.stepId(), properties.getPreflight().getAsrModel(), review.inputFingerprint(),
                providerTaskId, null
        );
        if (inserted < 0 || inserted > 1) {
            throw new IllegalStateException("ASR 调用记录保存失败");
        }
    }

    @Transactional
    public int replaceTranscript(PreflightReviewRecord review,
                                 PreflightStepRecord step,
                                 SpeechRecognitionProvider.TranscriptionResult result,
                                 Long usageSeconds,
                                 BigDecimal actualCost) {
        mapper.deleteEvidenceByStep(review.reviewId(), step.stepId());
        int count = 0;
        for (SpeechRecognitionProvider.Segment segment : result.segments()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (segment.language() != null) metadata.put("language", segment.language());
            if (segment.speaker() != null) metadata.put("speaker", segment.speaker());
            TimelineEvidenceRecord evidence = new TimelineEvidenceRecord(
                    null, UUID.randomUUID().toString(), review.reviewId(), review.versionId(), "TRANSCRIPT",
                    segment.startMs(), segment.endMs(), segment.text(),
                    segment.confidence() == null ? null : BigDecimal.valueOf(segment.confidence()),
                    null, false, step.stepId(), metadata.isEmpty() ? null : json(metadata)
            );
            if (mapper.insertEvidence(evidence) != 1) throw new IllegalStateException("转写时间轴保存失败");
            count++;
        }
        String outputRef = json(Map.of(
                "segmentCount", count,
                "usageSeconds", usageSeconds == null ? 0L : usageSeconds
        ));
        if (mapper.finishStep(review.reviewId(), "TRANSCRIBE", "SUCCEEDED", outputRef, null, null) != 1) {
            throw new IllegalStateException("ASR 步骤完成状态保存失败");
        }
        mapper.completeAsrCall(review.reviewId(), step.providerTaskId(),
                usageSeconds == null ? null : usageSeconds * 1000L, actualCost);
        return count;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("ASR 结果摘要序列化失败", exception);
        }
    }

    public static class AmbiguousSubmissionException extends RuntimeException {
        public AmbiguousSubmissionException(String message) {
            super(message);
        }
    }
}
