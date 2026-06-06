package com.link.linkagent.knowledge.model;

/**
 * 跨分区视频案例优质评论 / 弹幕子表（creator_reference_video_item）的数据库记录对象。
 * 结构对标反馈侧 creator_feedback_item，但外键是 video_id（跨任务）而非 task_id。
 * 5.1b 只写入清洗后「非噪声且正 / 负向」的优质条目，所以 is_noise 恒为 DB 默认 0、不在本对象出现；
 * 向量索引字段（embedding_*）同样走 DB 默认，留给 5.2 的子表向量化。
 */
public class ReferenceVideoItemRecord {

    private Long id;
    private String itemId;
    private String videoId;
    private String sourceType;
    private String content;
    private String sentiment;
    private Long likeCount;
    private Integer replyCount;
    private String occurTimeText;
    private String reason;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getVideoId() {
        return videoId;
    }

    public void setVideoId(String videoId) {
        this.videoId = videoId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSentiment() {
        return sentiment;
    }

    public void setSentiment(String sentiment) {
        this.sentiment = sentiment;
    }

    public Long getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Long likeCount) {
        this.likeCount = likeCount;
    }

    public Integer getReplyCount() {
        return replyCount;
    }

    public void setReplyCount(Integer replyCount) {
        this.replyCount = replyCount;
    }

    public String getOccurTimeText() {
        return occurTimeText;
    }

    public void setOccurTimeText(String occurTimeText) {
        this.occurTimeText = occurTimeText;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
