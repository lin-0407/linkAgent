package com.link.linkagent.creator.feedback.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 重建当前任务反馈证据索引响应。
 * <p>
 * 同时返回 {@code ragEnabled} 和 {@code vectorStoreReady} 两个状态，是为了让前端区分“业务开关没开”
 * 和“Milvus 基础设施没就绪”这两种不同原因；部分失败时不报错，而是写入 {@code failedCount} 和
 * {@code warnings}，让用户能看到具体哪几条没索引成功。
 */
public record CreatorFeedbackEvidenceIndexResponse(
        String taskId,
        boolean ragEnabled,
        boolean vectorStoreReady,
        int requestedCount,
        int indexedCount,
        int skippedCount,
        int failedCount,
        List<String> warnings,
        LocalDateTime createTime
) {
}
