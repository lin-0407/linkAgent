package com.link.linkagent.knowledge.model;

import java.util.List;

/**
 * 案例库检索响应（阶段 5.2a；5.2b 增补 strategy / enhancedQueries）。
 * <p>
 * mode 回显本次实际走的检索模式：VECTOR（向量命中足够）/ SQL（RAG 关或向量库不可用，纯关键词兜底）/
 * VECTOR_WITH_SQL_FALLBACK（向量命中不足，合并 SQL 兜底）。
 * <p>
 * strategy 回显本次<b>实际生效</b>的查询增强策略（5.2b）；走 SQL 兜底时未做增强，回显 NONE。
 * enhancedQueries 回显增强后实际用于向量检索的查询（NONE / SQL 路径为空），便于核对增强是否合理、定位召回问题。
 * <p>
 * items 复用案例列表项 {@link ReferenceVideoResponse}：检索结果与列表是同一种「案例卡片」，前端可直接复用卡片渲染，
 * 不为检索单独造一套展示模型（简单优先）。顺序即相关性（向量按相似度、SQL 按质量分）。
 */
public record ReferenceVideoSearchResponse(
        String mode,
        String strategy,
        List<String> enhancedQueries,
        List<ReferenceVideoResponse> items
) {
}
