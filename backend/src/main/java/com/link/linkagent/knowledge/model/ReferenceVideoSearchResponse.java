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
 * 不为检索单独造一套展示模型（简单优先）。顺序即相关性（向量按相似度、SQL 按质量信号）。
 * <p>
 * evidence（5.2c-2，方案 a）：small-to-big 父子召回中「命中的子条目证据」，按 videoId 分组、与 items 平级。
 * 只含最终卡片里「有子命中」者；无子召回 / SQL 降级路径回空列表。放顶层而非内嵌卡片，是为了让 items 继续复用
 * {@link ReferenceVideoResponse}（5.2a/b 卡片渲染零改动）。前端按 videoId 关联展示「这个案例被哪条观众原话召回」。
 * <p>
 * reranked（5.2e）：本次结果是否经 qwen3-rerank 精排重排过。关闭 / 失败 / SQL 降级路径为 false（保持原检索顺序），
 * 便于核对「精排是否实际生效」（沿用「回显实际生效」的排查友好原则）。
 */
public record ReferenceVideoSearchResponse(
        String mode,
        String strategy,
        List<String> enhancedQueries,
        List<ReferenceVideoResponse> items,
        List<ReferenceVideoEvidence> evidence,
        boolean reranked
) {
}
