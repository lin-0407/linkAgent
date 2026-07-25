package com.link.linkagent.creator.media.preflight.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 发布前试映任务持久化记录。 */
public record PreflightReviewRecord(
        Long id,
        String reviewId,
        String taskId,
        String versionId,
        String ownerId,
        String processingJobId,
        String idempotencyKey,
        String reviewFocus,
        String status,
        String currentStep,
        Integer progressPercent,
        Long eventSequence,
        Boolean cancelRequested,
        Integer attemptCount,
        Integer maxAttempts,
        LocalDateTime nextRunAt,
        String leaseOwner,
        LocalDateTime leaseExpiresAt,
        String inputFingerprint,
        String providerSnapshot,
        String capabilityGaps,
        String executiveSummary,
        BigDecimal estimatedCostUsd,
        BigDecimal actualCostUsd,
        Long usageSeconds,
        String currency,
        String errorCode,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
