package com.link.linkagent.creator.report.model;

import java.time.LocalDateTime;

public record CreatorReportResponse(
        Long id,
        String reportId,
        String taskId,
        String contentSummary,
        String coreSellingPoints,
        String titleDescriptionReview,
        String audienceFeedbackSummary,
        String competitorComparison,
        String controversyAndMisunderstanding,
        String nextActionSuggestions,
        String creatorPreferenceInsight,
        String overallConclusion,
        String rawOutput,
        String parseStatus,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
