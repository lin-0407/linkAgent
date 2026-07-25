package com.link.linkagent.creator.media.preflight.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 页面刷新和 SSE 重连时使用的完整试映快照。 */
public record PreflightReviewResponse(
        String reviewId,
        String taskId,
        String versionId,
        String status,
        String currentStep,
        int progressPercent,
        long eventSequence,
        boolean cancelRequested,
        int attemptCount,
        int maxAttempts,
        String reviewFocus,
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
        LocalDateTime updateTime,
        List<Step> steps,
        List<Evidence> evidence,
        List<Issue> issues
) {
    public record Step(String stepId,
                       String stepType,
                       int sequenceNo,
                       String status,
                       int attemptCount,
                       String providerTaskId,
                       String errorCode,
                       String errorMessage) {
    }

    public record Evidence(String evidenceId,
                           String sourceType,
                           long startMs,
                           long endMs,
                           String content,
                           BigDecimal confidence,
                           String assetId,
                           boolean assetAvailable,
                           String metadataJson) {
    }

    public record Issue(String issueId,
                        String issueType,
                        String dimension,
                        String title,
                        String description,
                        long startMs,
                        long endMs,
                        String severity,
                        BigDecimal confidence,
                        List<String> evidenceRefs,
                        String suggestedAction,
                        boolean needsHumanReview) {
    }
}
