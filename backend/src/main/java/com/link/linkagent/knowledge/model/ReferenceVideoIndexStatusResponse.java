package com.link.linkagent.knowledge.model;

import java.time.LocalDateTime;

/**
 * 案例库向量索引状态响应（阶段 5.1c）。
 * <p>
 * {@code retrievalMode} 是「如果现在检索，预计走哪种模式」的预测值：RAG 启用、向量库就绪且已有 INDEXED 案例时
 * 预测走向量检索（VECTOR），否则按 SQL 检索（SQL）展示——让前端在检索前就能提示当前检索方式（检索链路本体在 5.2）。
 * {@code lastIndexedAt} 为空表示还没有任何案例被成功索引过。
 */
public record ReferenceVideoIndexStatusResponse(
        boolean ragEnabled,
        boolean vectorStoreReady,
        long totalCount,
        long indexedCount,
        long pendingCount,
        long failedCount,
        LocalDateTime lastIndexedAt,
        String retrievalMode
) {
}
