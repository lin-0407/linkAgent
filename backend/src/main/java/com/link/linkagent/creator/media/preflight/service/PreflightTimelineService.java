package com.link.linkagent.creator.media.preflight.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.media.preflight.mapper.PreflightReviewMapper;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.model.TimelineEvidenceRecord;
import com.link.linkagent.creator.media.processing.mapper.MediaProcessingMapper;
import com.link.linkagent.creator.media.processing.model.MediaProcessingAssetRecord;
import com.link.linkagent.creator.media.processing.model.MediaProcessingJobRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 把 P0-2 关键画面和确定性信号追加到统一毫秒时间轴。
 * 转写证据由 TRANSCRIBE 步骤单独保存，重建本步骤时不会删除已经付费取得的 ASR 结果。
 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class PreflightTimelineService {

    private final PreflightReviewMapper preflightMapper;
    private final MediaProcessingMapper processingMapper;
    private final ObjectMapper objectMapper;

    public PreflightTimelineService(PreflightReviewMapper preflightMapper,
                                    MediaProcessingMapper processingMapper,
                                    ObjectMapper objectMapper) {
        this.preflightMapper = preflightMapper;
        this.processingMapper = processingMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int rebuild(PreflightReviewRecord review, PreflightStepRecord step) {
        preflightMapper.deleteEvidenceByStep(review.reviewId(), step.stepId());
        int count = appendKeyFrames(review, step);
        count += appendSignals(review, step);
        return count;
    }

    private int appendKeyFrames(PreflightReviewRecord review, PreflightStepRecord step) {
        int count = 0;
        for (MediaProcessingAssetRecord asset : processingMapper.listAssets(review.processingJobId())) {
            if (!"KEYFRAME".equals(asset.assetType()) || asset.timestampMs() == null) continue;
            String metadata = json(Map.of("sequenceNo", asset.sequenceNo() == null ? 0 : asset.sequenceNo()));
            insert(review, step, "KEY_FRAME", asset.timestampMs(), asset.timestampMs(),
                    "关键画面", null, asset.assetId(), true, metadata);
            count++;
        }
        return count;
    }

    private int appendSignals(PreflightReviewRecord review, PreflightStepRecord step) {
        MediaProcessingJobRecord processing = processingMapper.findJob(
                        review.taskId(), review.ownerId(), review.versionId(), review.processingJobId())
                .orElseThrow(() -> new IllegalStateException("媒体预处理任务不存在"));
        if (processing.signalSummaryJson() == null || processing.signalSummaryJson().isBlank()) return 0;
        try {
            JsonNode root = objectMapper.readTree(processing.signalSummaryJson());
            int count = appendRanges(review, step, root.path("black"), "BLACK", "检测到黑屏片段");
            count += appendRanges(review, step, root.path("silence"), "SILENCE", "检测到静音片段");
            count += appendRanges(review, step, root.path("freeze"), "FREEZE", "检测到画面冻结片段");
            if (root.path("meanVolumeDb").isNumber() || root.path("maxVolumeDb").isNumber()) {
                String metadata = objectMapper.writeValueAsString(Map.of(
                        "meanVolumeDb", root.path("meanVolumeDb").isNumber()
                                ? root.path("meanVolumeDb").asDouble() : 0D,
                        "maxVolumeDb", root.path("maxVolumeDb").isNumber()
                                ? root.path("maxVolumeDb").asDouble() : 0D
                ));
                insert(review, step, "VOLUME", 0L, 0L, "音轨整体音量摘要",
                        null, null, false, metadata);
                count++;
            }
            return count;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("媒体信号摘要无法解析", exception);
        }
    }

    private int appendRanges(PreflightReviewRecord review,
                             PreflightStepRecord step,
                             JsonNode ranges,
                             String sourceType,
                             String content) {
        if (!ranges.isArray()) return 0;
        int count = 0;
        Iterator<JsonNode> iterator = ranges.elements();
        while (iterator.hasNext()) {
            JsonNode range = iterator.next();
            long startMs = Math.max(0L, Math.round(range.path("startSeconds").asDouble() * 1000D));
            long endMs = range.path("endSeconds").isNumber()
                    ? Math.max(startMs, Math.round(range.path("endSeconds").asDouble() * 1000D))
                    : startMs;
            insert(review, step, sourceType, startMs, endMs, content,
                    null, null, false, range.toString());
            count++;
        }
        return count;
    }

    private void insert(PreflightReviewRecord review,
                        PreflightStepRecord step,
                        String sourceType,
                        long startMs,
                        long endMs,
                        String content,
                        java.math.BigDecimal confidence,
                        String assetId,
                        boolean assetAvailable,
                        String metadata) {
        TimelineEvidenceRecord evidence = new TimelineEvidenceRecord(
                null,
                UUID.randomUUID().toString(),
                review.reviewId(),
                review.versionId(),
                sourceType,
                startMs,
                endMs,
                content,
                confidence,
                assetId,
                assetAvailable,
                step.stepId(),
                metadata
        );
        if (preflightMapper.insertEvidence(evidence) != 1) {
            throw new IllegalStateException("时间轴证据保存失败");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("时间轴元数据序列化失败", exception);
        }
    }
}
