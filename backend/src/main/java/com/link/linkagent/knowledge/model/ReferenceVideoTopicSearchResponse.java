package com.link.linkagent.knowledge.model;

import java.util.List;

/**
 * 主题优先案例检索响应。
 * <p>
 * cards 是当前页展示的视频卡片，matchedTopics 是本次 RAG 实际命中的主题中块，用于解释推荐来源。
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
        List<ReferenceVideoResponse> cards
) {
}
