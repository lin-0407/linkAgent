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
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
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
    private final MediaUploadMapper uploadMapper;

    public PreflightTimelineService(PreflightReviewMapper preflightMapper,
                                    MediaProcessingMapper processingMapper,
                                    ObjectMapper objectMapper,
                                    MediaUploadMapper uploadMapper) {
        this.preflightMapper = preflightMapper;
        this.processingMapper = processingMapper;
        this.objectMapper = objectMapper;
        this.uploadMapper = uploadMapper;
    }

    @Transactional
    public int rebuild(PreflightReviewRecord review, PreflightStepRecord step) {
        preflightMapper.deleteEvidenceByStep(review.reviewId(), step.stepId());
        // 读取试映绑定的确切版本，不能拿后来上传的原片音轨状态解释旧版本。
        DraftVideoRecord draft = uploadMapper.findDraftVideoByVersion(review.taskId(), review.ownerId(), review.versionId())
                .orElseThrow(() -> new IllegalStateException("试映对应的原片版本不存在"));
        int count = appendAudioTrack(review, step, draft);
        count += appendKeyFrames(review, step);
        count += appendSignals(review, step, draft.durationMs());
        return count;
    }

    /** 代理视频主动去掉音轨，原片的探测事实必须单独进入模型可读的证据。 */
    private int appendAudioTrack(PreflightReviewRecord review, PreflightStepRecord step, DraftVideoRecord draft) {
        if (draft.durationMs() == null || draft.durationMs() <= 0) {
            throw new IllegalStateException("原片缺少有效探测时长");
        }
        String content = draft.hasAudio() == null ? "FFprobe 未记录原片音轨状态，不能判断原片有无音轨。"
                : draft.hasAudio() ? "FFprobe 已确认原片存在音轨（编码："
                    + (draft.audioCodec() == null ? "未记录" : draft.audioCodec()) + "），不是无音轨视频。"
                : "FFprobe 已确认原片没有音轨。";
        content += "分析代理视频和复核短片主动去掉音轨，与原片音轨状态无关；没有转写文本不代表原片无声。";
        var metadata = objectMapper.createObjectNode();
        metadata.put("hasAudio", draft.hasAudio());
        metadata.put("audioCodec", draft.audioCodec());
        insert(review, step, "AUDIO_TRACK", 0L, draft.durationMs(), content,
                null, null, false, metadata.toString());
        return 1;
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

    private int appendSignals(PreflightReviewRecord review, PreflightStepRecord step, long durationMs) {
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
                // 模型提示词只读 content，数值不能仅藏在 metadata；未测得的值也不能伪装成 0 dB。
                var metadata = objectMapper.createObjectNode();
                metadata.set("meanVolumeDb", root.path("meanVolumeDb").isNumber()
                        ? root.path("meanVolumeDb") : objectMapper.nullNode());
                metadata.set("maxVolumeDb", root.path("maxVolumeDb").isNumber()
                        ? root.path("maxVolumeDb") : objectMapper.nullNode());
                String content = "原片音量检测：平均音量 "
                        + (root.path("meanVolumeDb").isNumber() ? root.path("meanVolumeDb").asText() + " dB" : "未测得")
                        + "，峰值音量 "
                        + (root.path("maxVolumeDb").isNumber() ? root.path("maxVolumeDb").asText() + " dB" : "未测得")
                        + "。音量检测不能判断声音是音乐、背景音还是人声。";
                insert(review, step, "VOLUME", 0L, durationMs, content,
                        null, null, false, metadata.toString());
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
