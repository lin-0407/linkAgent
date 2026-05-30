package com.link.linkagent.creator.feedback.model;

import java.time.LocalDateTime;

public record CreatorFeedbackItemResponse(
        String itemId,
        String sourceType,
        String sourceLabel,
        String content,
        String occurTimeText,
        Long likeCount,
        Integer replyCount,
        String category,
        String categoryLabel,
        String sentiment,
        String sentimentLabel,
        boolean noise,
        String reason,
        LocalDateTime createTime
) {
}
