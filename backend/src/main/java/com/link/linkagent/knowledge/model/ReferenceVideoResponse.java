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

    /**
     * 由数据库记录对象组装列表项响应。供检索链路（5.2）复用，避免在多处重复手写 21 个字段的构造。
     */
    public static ReferenceVideoResponse from(ReferenceVideoRecord record) {
        return new ReferenceVideoResponse(
                record.getId(),
                record.getVideoId(),
                record.getBvId(),
                record.getTier(),
                record.getCategory(),
                record.getTitle(),
                record.getDescription(),
                record.getTags(),
                record.getViewCount(),
                record.getLikeCount(),
                record.getCoinCount(),
                record.getFavoriteCount(),
                record.getDanmakuCount(),
                record.getReplyCount(),
                record.getHighlightSummary(),
                record.getQualityScore(),
                record.getSource(),
                record.getPublishTimeText(),
                record.getEmbeddingStatus(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
