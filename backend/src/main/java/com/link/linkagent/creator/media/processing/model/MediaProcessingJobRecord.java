package com.link.linkagent.creator.media.processing.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * creator_media_processing_job 数据库记录。
 */
public record MediaProcessingJobRecord(
        Long id,
        String jobId,
        String versionId,
        String taskId,
        String ownerId,
        Integer frameIntervalSeconds,
        String targetResolution,
        Integer targetHeight,
        String modelPlan,
        Boolean includeAsr,
        String pricingVersion,
        Integer estimatedFrameCount,
        Long estimatedVisualInputTokens,
        Long estimatedVisualOutputTokens,
        Long estimatedAsrSeconds,
        BigDecimal estimatedVisualCostUsd,
        BigDecimal estimatedAsrCostUsd,
        BigDecimal estimatedTotalCostUsd,
        String status,
        String currentStep,
        Integer progressPercent,
        Integer attemptCount,
        String leaseOwner,
        LocalDateTime leaseExpiresAt,
        String signalSummaryJson,
        String failureMessage,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
