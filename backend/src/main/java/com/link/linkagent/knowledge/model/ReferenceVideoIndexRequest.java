package com.link.linkagent.knowledge.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 重建案例库向量索引请求（阶段 5.1c）。
 * <p>
 * maxItems 允许为空：为空时回落到 {@code knowledge.rag.max-index-items} 配置默认值。
 * 设上限 1000，是为了防止误触发把整库案例一次性送 Embedding 造成高成本（演示环境成本保护）。
 */
public record ReferenceVideoIndexRequest(
        @Min(value = 1, message = "单次索引条数至少为1")
        @Max(value = 1000, message = "单次索引条数不能超过1000")
        Integer maxItems
) {
}
