package com.link.linkagent.knowledge.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 案例库列表项响应。
 * tags 直接返回存储时的 JSON 字符串、由前端解析，沿用项目里竞品报告等 JSON 字段的既有约定，避免后端额外反序列化。
 * 保留既有评分字段名以兼容调用方：rawQualityScore 为脚本基础分，qualityScore 为分区互动分。
 * qualityScoreReliable 仅表示统计完整可计算；categoryRank 为整个有效分区内的并列名次。
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
        LocalDateTime updateTime,
        LocalDateTime publishedAt,
        LocalDateTime statsFetchedAt,
        BigDecimal videoIndexScore,
        String videoIndexVersion,
        BigDecimal collectionMinIndex,
        Integer categoryRank
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
                record.getUpdateTime(),
                record.getPublishedAt(), record.getStatsFetchedAt(), record.getVideoIndexScore(),
                record.getVideoIndexVersion(), record.getCollectionMinIndex(), record.getCategoryRank()
        );
    }
}
