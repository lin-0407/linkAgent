package com.link.linkagent.creator.feedback.model;

import java.time.LocalDateTime;

public record CreatorFeedbackReportResponse(
        Long id,
        String reportId,
        String taskId,
        String feedbackSummary,
        String hotTopics,
        String sentimentSummary,
        String controversyPoints,
        String misunderstandingPoints,
        String nextContentSuggestions,
        String interactionSuggestions,
        String creatorFeedbackDilemma,
        String audienceCoreConcern,
        String misunderstandingSourceAnalysis,
        String feedbackActionPlan,
        String rawOutput,
        String parseStatus,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
