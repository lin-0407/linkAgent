package com.link.linkagent.knowledge.model;

/**
 * 质量打分专用投影（阶段 5.1c）。
 * <p>
 * 打分只需要「父表 6 项热度 + 子表正/负向条数」，不需要完整 {@link ReferenceVideoRecord}，
 * 所以单独定义一个瘦投影：用一个 LEFT JOIN + 条件 SUM 把这两部分一次查齐，避免「先查视频再逐条查子表」的 N+1。
 * posCount / negCount 用 SUM(CASE ...) 聚合而来，无子条目时为 0（非 null），故用 long 承载。
 */
public class ReferenceVideoScoringRow {

    private String videoId;
    private Long viewCount;
    private Long likeCount;
    private Long coinCount;
    private Long favoriteCount;
    private Long danmakuCount;
    private Long replyCount;
    /** 该视频清洗后优质「正向」条目数；归一化情绪因子的分子。 */
    private long posCount;
    /** 该视频清洗后优质「负向」条目数。posCount + negCount 即有效情绪样本量 n。 */
    private long negCount;

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

    public long getPosCount() {
        return posCount;
    }

    public void setPosCount(long posCount) {
        this.posCount = posCount;
    }

    public long getNegCount() {
        return negCount;
    }

    public void setNegCount(long negCount) {
        this.negCount = negCount;
    }
}
