package com.link.linkagent.creator.bilibili.model;

import java.time.LocalDateTime;

/**
 * B站视频缓存表（creator_bilibili_video）数据库记录。
 * 使用普通 JavaBean，避免 MyBatis 对不可变 record 执行 setter 映射时失败。
 */
public class BilibiliVideoRecord {

    private Long id;
    private String videoId;
    private String bilibiliUid;
    private String bvid;
    private Long aid;
    private String title;
    private String coverUrl;
    private LocalDateTime publishTime;
    private Long viewCount;
    private Long likeCount;
    private Long coinCount;
    private Long favoriteCount;
    private Long shareCount;
    private String syncStatus;
    private LocalDateTime lastSyncTime;
    private String rawSnapshot;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** MyBatis 查询时先创建空对象，再逐字段调用 setter。 */
    public BilibiliVideoRecord() {
    }

    /** 业务同步视频缓存时使用全参构造，保证指标快照字段与对象一致。 */
    public BilibiliVideoRecord(Long id, String videoId, String bilibiliUid, String bvid, Long aid,
                               String title, String coverUrl, LocalDateTime publishTime, Long viewCount,
                               Long likeCount, Long coinCount, Long favoriteCount, Long shareCount,
                               String syncStatus, LocalDateTime lastSyncTime, String rawSnapshot,
                               LocalDateTime createTime, LocalDateTime updateTime) {
        this.id = id;
        this.videoId = videoId;
        this.bilibiliUid = bilibiliUid;
        this.bvid = bvid;
        this.aid = aid;
        this.title = title;
        this.coverUrl = coverUrl;
        this.publishTime = publishTime;
        this.viewCount = viewCount;
        this.likeCount = likeCount;
        this.coinCount = coinCount;
        this.favoriteCount = favoriteCount;
        this.shareCount = shareCount;
        this.syncStatus = syncStatus;
        this.lastSyncTime = lastSyncTime;
        this.rawSnapshot = rawSnapshot;
        this.createTime = createTime;
        this.updateTime = updateTime;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }
    public String getBilibiliUid() { return bilibiliUid; }
    public void setBilibiliUid(String bilibiliUid) { this.bilibiliUid = bilibiliUid; }
    public String getBvid() { return bvid; }
    public void setBvid(String bvid) { this.bvid = bvid; }
    public Long getAid() { return aid; }
    public void setAid(Long aid) { this.aid = aid; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public LocalDateTime getPublishTime() { return publishTime; }
    public void setPublishTime(LocalDateTime publishTime) { this.publishTime = publishTime; }
    public Long getViewCount() { return viewCount; }
    public void setViewCount(Long viewCount) { this.viewCount = viewCount; }
    public Long getLikeCount() { return likeCount; }
    public void setLikeCount(Long likeCount) { this.likeCount = likeCount; }
    public Long getCoinCount() { return coinCount; }
    public void setCoinCount(Long coinCount) { this.coinCount = coinCount; }
    public Long getFavoriteCount() { return favoriteCount; }
    public void setFavoriteCount(Long favoriteCount) { this.favoriteCount = favoriteCount; }
    public Long getShareCount() { return shareCount; }
    public void setShareCount(Long shareCount) { this.shareCount = shareCount; }
    public String getSyncStatus() { return syncStatus; }
    public void setSyncStatus(String syncStatus) { this.syncStatus = syncStatus; }
    public LocalDateTime getLastSyncTime() { return lastSyncTime; }
    public void setLastSyncTime(LocalDateTime lastSyncTime) { this.lastSyncTime = lastSyncTime; }
    public String getRawSnapshot() { return rawSnapshot; }
    public void setRawSnapshot(String rawSnapshot) { this.rawSnapshot = rawSnapshot; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
