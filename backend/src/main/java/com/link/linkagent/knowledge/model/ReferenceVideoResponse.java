package com.link.linkagent.knowledge.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 案例库列表项响应。
 * tags 直接返回存储时的 JSON 字符串、由前端解析，沿用项目里竞品报告等 JSON 字段的既有约定，避免后端额外反序列化。
 * highlightSummary 与 qualityScore 在 5.1a 恒为空，分别等 5.1b、5.1c 写入。
 */
public record ReferenceVideoResponse(
        Long id,
        String videoId,
        String bvId,
        String tier,
        String category,
        String title,
        String description,
        String tags,
        Long viewCount,
        Long likeCount,
        Long coinCount,
        Long favoriteCount,
        Long danmakuCount,
        Long replyCount,
        String highlightSummary,
        BigDecimal qualityScore,
        String source,
        String publishTimeText,
        String embeddingStatus,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
