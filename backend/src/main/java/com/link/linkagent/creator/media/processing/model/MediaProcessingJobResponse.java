package com.link.linkagent.creator.media.processing.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 页面轮询使用的完整媒体预处理快照。
 */
public record MediaProcessingJobResponse(
        String jobId,
        String versionId,
        String taskId,
        int frameIntervalSeconds,
        String targetResolution,
        String modelPlan,
        boolean includeAsr,
        String pricingVersion,
        int estimatedFrameCount,
        long estimatedVisualInputTokens,
        long estimatedVisualOutputTokens,
        long estimatedAsrSeconds,
        BigDecimal estimatedVisualCostUsd,
        BigDecimal estimatedAsrCostUsd,
        BigDecimal estimatedTotalCostUsd,
        String status,
        String currentStep,
        int progressPercent,
        int attemptCount,
        String failureMessage,
        JsonNode signalSummary,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        List<Step> steps,
        List<Asset> assets,
        String costNotice
) {

    public record Step(
            String stepCode,
            String stepName,
            int sequenceNo,
            String status,
            int progressPercent,
            String outputSummary,
            String failureMessage
    ) {
    }

    public record Asset(
            String assetId,
            String assetType,
            String contentType,
            long fileSize,
            Integer sequenceNo,
            Long timestampMs,
            Integer width,
            Integer height,
            Long durationMs
    ) {
    }
}
