package com.link.linkagent.creator.feedback.model;

import java.time.LocalDateTime;

public record CreatorFeedbackMetricResponse(
        String metricId,
        Long viewCount,
        Long favoriteCount,
        Long coinCount,
        Long likeCount,
        Long shareCount,
        String source,
        LocalDateTime createTime
) {
}
