package com.link.linkagent.knowledge.model;

/**
 * 主题中块命中摘要。
 * <p>
 * 返回它是为了让前端解释“为什么这些卡片被推荐”，而不是只展示一组按质量信号排序的视频。
 */
public record ReferenceVideoMatchedTopic(
        String chunkId,
        String videoId,
        String chunkType,
        String chunkTitle,
        String preview
) {
}
