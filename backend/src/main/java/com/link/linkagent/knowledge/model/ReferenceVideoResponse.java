package com.link.linkagent.knowledge.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 案例库列表项响应。
 * tags 直接返回存储时的 JSON 字符串、由前端解析，沿用项目里竞品报告等 JSON 字段的既有约定，避免后端额外反序列化。
 * highlightSummary 与质量分相关字段在 5.1a 恒为空 / 默认值，分别等 5.1b、5.1c 写入。
 * rawQualityScore 是单视频独立原始分；qualityScore 是同分区相对分，只有 qualityScoreReliable=true 时才适合展示。
 */
public record ReferenceVideoResponse(
        Long id,
        String videoId,
        String bvId,
        String coverUrl,
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
        BigDecimal rawQualityScore,
        BigDecimal qualityScore,
        int qualitySampleCount,
        boolean qualityScoreReliable,
        String source,
        String publishTimeText,
        String embeddingStatus,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {

    /**
     * 由数据库记录对象组装列表项响应。供检索链路（5.2）复用，避免在多处重复手写整张卡片字段的构造。
     */
    public static ReferenceVideoResponse from(ReferenceVideoRecord record) {
        return new ReferenceVideoResponse(
                record.getId(),
                record.getVideoId(),
                record.getBvId(),
                BilibiliCoverUrlPolicy.normalize(record.getCoverUrl()),
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
                record.getRawQualityScore(),
                record.getQualityScore(),
                record.getQualitySampleCount(),
                record.isQualityScoreReliable(),
                record.getSource(),
                record.getPublishTimeText(),
                record.getEmbeddingStatus(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
