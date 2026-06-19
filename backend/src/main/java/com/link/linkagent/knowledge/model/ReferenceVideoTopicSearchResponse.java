package com.link.linkagent.knowledge.model;

import java.util.List;

/**
 * 主题优先案例检索响应。
 * <p>
 * cards 是当前页展示的视频卡片，matchedTopics 是本次 RAG 实际命中的主题中块，用于解释推荐来源。
 * evidence 是当前页卡片对应的相关评论 / 弹幕证据，用于解释排序依据。
 * reranked 表示 top20 候选是否经过 rerank 精排；关闭、失败或 SQL 兜底时为 false。
 */
public record ReferenceVideoTopicSearchResponse(
        String mode,
        String strategy,
        List<String> enhancedQueries,
        int page,
        int size,
        int maxPage,
        boolean hasMore,
        List<ReferenceVideoMatchedTopic> matchedTopics,
        List<ReferenceVideoEvidence> evidence,
        List<ReferenceVideoResponse> cards,
        boolean reranked
) {
}
