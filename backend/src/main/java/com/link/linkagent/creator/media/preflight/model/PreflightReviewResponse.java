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
        List<Issue> issues,
        List<AudienceScreening> audienceScreenings,
        List<EditTask> editTasks
) {
    @com.fasterxml.jackson.annotation.JsonProperty("reviewScope")
    public String reviewScope(){return "VERSION_MEDIA_ONLY";}
    @com.fasterxml.jackson.annotation.JsonProperty("publishingContextIncluded")
    public boolean publishingContextIncluded(){return false;}
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
        @com.fasterxml.jackson.annotation.JsonProperty("claimType")
        public String claimType(){return sourceType!=null && java.util.Set.of("AUDIO_TRACK","KEY_FRAME","BLACK","SILENCE","FREEZE","VOLUME")
                .contains(sourceType)?"DETERMINISTIC_FACT":"MODEL_OBSERVATION";}
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
                        boolean needsHumanReview,
                        List<String> affectedPersonas,
                        String userDisposition,
                        String ignoreReason) {
        @com.fasterxml.jackson.annotation.JsonProperty("claimType")
        public String claimType(){return "MODEL_OBSERVATION";}
    }

    public record AudienceScreening(String screeningId,
                                    String personaType,
                                    String overallReaction,
                                    List<String> interestPoints,
                                    List<String> confusionPoints,
                                    List<String> dropRisks,
                                    List<String> evidenceRefs,
                                    BigDecimal confidence) {
        @com.fasterxml.jackson.annotation.JsonProperty("claimType")
        public String claimType(){return "AUDIENCE_HYPOTHESIS";}
    }

    public record EditTask(String editTaskId,
                           String issueId,
                           String title,
                           String action,
                           long startMs,
                           long endMs,
                           String priority,
                           String targetOutcome,
                           String status,
                           String userNote,
                           LocalDateTime completedAt,
                           LocalDateTime updateTime) {
    }
}
