package com.link.linkagent.knowledge.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 重建案例库向量索引响应（阶段 5.1c）。
 * <p>
 * 同时返回 {@code ragEnabled} 与 {@code vectorStoreReady}，让前端区分「业务开关没开」和「Milvus 基础设施没就绪」
 * 两种不同原因；部分批次失败不报错，而是写入 {@code failedCount} 与 {@code warnings}，让用户看到具体哪几批没成功。
 * 与反馈索引响应结构一致，只是去掉了 taskId（案例库跨任务、是全局索引）。
 */
public record ReferenceVideoIndexResponse(
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
