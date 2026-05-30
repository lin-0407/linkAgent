package com.link.linkagent.creator.feedback.model;

import java.util.List;

public record CreatorFeedbackDashboardResponse(
        String taskId,
        long commentCount,
        long danmakuCount,
        long noiseCount,
        CreatorFeedbackMetricResponse metric,
        List<CreatorFeedbackStatResponse> commentCategoryStats,
        List<CreatorFeedbackStatResponse> danmakuCategoryStats,
        List<CreatorFeedbackStatResponse> sentimentStats,
        List<CreatorFeedbackKeywordResponse> keywords,
        List<CreatorFeedbackTimelineResponse> danmakuTimeline,
        List<CreatorFeedbackItemResponse> topCommentItems,
        List<CreatorFeedbackItemResponse> recentItems,
        List<String> warnings
) {
}
