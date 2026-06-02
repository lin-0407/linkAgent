package com.link.linkagent.creator.feedback.model;

import java.time.LocalDateTime;

/**
 * 当前任务证据索引状态响应。
 * <p>
 * {@code retrievalMode} 表示“如果现在追问，预计走哪种检索模式”，让前端在用户提问前就能提示当前是 SQL 检索
 * 还是向量检索；{@code lastIndexedAt} 为空表示当前任务还没有任何明细被成功索引过。
 */
public record CreatorFeedbackEvidenceIndexStatusResponse(
        String taskId,
        boolean ragEnabled,
        boolean vectorStoreReady,
        long totalItems,
        long indexedCount,
        long pendingCount,
        long failedCount,
        LocalDateTime lastIndexedAt,
        String retrievalMode
) {
}
