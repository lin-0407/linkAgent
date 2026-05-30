package com.link.linkagent.creator.feedback.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论弹幕追问响应。
 * evidenceItems 明确返回本次回答参考的任务内证据，为后续替换成向量检索保留同一层响应结构。
 */
public record CreatorFeedbackChatResponse(
        String taskId,
        String question,
        String answer,
        List<CreatorFeedbackItemResponse> evidenceItems,
        boolean reportUsed,
        String retrievalMode,
        boolean ragEnabled,
        LocalDateTime createTime
) {
}
