package com.link.linkagent.knowledge.model;

/**
 * 评分只取六项公开统计，避免关键词情绪、正文清洗数量影响分区互动分。
 */
public class ReferenceVideoScoringRow {

    private String videoId;
    private Long viewCount;
    private Long likeCount;
    private Long coinCount;
    private Long favoriteCount;
    private Long danmakuCount;
    private Long replyCount;

    public String getVideoId() {
        return videoId;
    }

    public void setVideoId(String videoId) {
        this.videoId = videoId;
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

}
