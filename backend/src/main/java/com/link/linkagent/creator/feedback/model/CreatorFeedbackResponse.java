package com.link.linkagent.creator.feedback.model;

import java.time.LocalDateTime;

public record CreatorFeedbackResponse(
        Long id,
        String feedbackId,
        String taskId,
        String commentSamples,
        String danmakuSamples,
        String extraContext,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
