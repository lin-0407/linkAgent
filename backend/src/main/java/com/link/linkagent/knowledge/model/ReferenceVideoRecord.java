package com.link.linkagent.knowledge.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 跨分区视频案例主表（creator_reference_video）的数据库记录对象。
 * 用普通 POJO 而非 record，是因为 MyBatis @Results 通过 setter 回填字段，record 的不可变特性不适合做映射载体。
 * 阶段 5.1a 只负责把案例落库，质量分相关字段与亮点摘要（highlight_summary）留空，分别在 5.1c、5.1b 补写。
 */
public class ReferenceVideoRecord {

    private Long id;
    private String videoId;
    private String bvId;
    private String tier;
    private String category;
    private String title;
    private String description;
    /** 标签以 JSON 字符串形态存储，避免为少量标签单独拆明细表（简单优先）。 */
    private String tags;
    private Long viewCount;
    private Long likeCount;
    private Long coinCount;
    private Long favoriteCount;
    private Long danmakuCount;
    private Long replyCount;
    private String highlightSummary;
    private BigDecimal rawQualityScore;
    private BigDecimal qualityScore;
    private int qualitySampleCount;
    private boolean qualityScoreReliable;
    private String source;
    private String publishTimeText;
    /** 向量索引状态，5.1a 阶段恒为 DB 默认值 PENDING，向量化在 5.1c 才真正写状态。 */
    private String embeddingStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVideoId() {
        return videoId;
    }

    public void setVideoId(String videoId) {
        this.videoId = videoId;
    }

    public String getBvId() {
        return bvId;
    }

    public void setBvId(String bvId) {
        this.bvId = bvId;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public Long getViewCount() {
        return viewCount;
    }

    public void setViewCount(Long viewCount) {
        this.viewCount = viewCount;
    }

    public Long getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Long likeCount) {
        this.likeCount = likeCount;
    }

    public Long getCoinCount() {
        return coinCount;
    }

    public void setCoinCount(Long coinCount) {
        this.coinCount = coinCount;
    }

    public Long getFavoriteCount() {
        return favoriteCount;
    }

    public void setFavoriteCount(Long favoriteCount) {
        this.favoriteCount = favoriteCount;
    }

    public Long getDanmakuCount() {
        return danmakuCount;
    }

    public void setDanmakuCount(Long danmakuCount) {
        this.danmakuCount = danmakuCount;
    }

    public Long getReplyCount() {
        return replyCount;
    }

    public void setReplyCount(Long replyCount) {
        this.replyCount = replyCount;
    }

    public String getHighlightSummary() {
        return highlightSummary;
    }

    public void setHighlightSummary(String highlightSummary) {
        this.highlightSummary = highlightSummary;
    }

    public BigDecimal getRawQualityScore() {
        return rawQualityScore;
    }

    public void setRawQualityScore(BigDecimal rawQualityScore) {
        this.rawQualityScore = rawQualityScore;
    }

    public BigDecimal getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(BigDecimal qualityScore) {
        this.qualityScore = qualityScore;
    }

    public int getQualitySampleCount() {
        return qualitySampleCount;
    }

    public void setQualitySampleCount(int qualitySampleCount) {
        this.qualitySampleCount = qualitySampleCount;
    }

    public boolean isQualityScoreReliable() {
        return qualityScoreReliable;
    }

    public void setQualityScoreReliable(boolean qualityScoreReliable) {
        this.qualityScoreReliable = qualityScoreReliable;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getPublishTimeText() {
        return publishTimeText;
    }

    public void setPublishTimeText(String publishTimeText) {
        this.publishTimeText = publishTimeText;
    }

    public String getEmbeddingStatus() {
        return embeddingStatus;
    }

    public void setEmbeddingStatus(String embeddingStatus) {
        this.embeddingStatus = embeddingStatus;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
