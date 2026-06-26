package com.link.linkagent.creator.profile.model;

import java.time.LocalDateTime;

/**
 * 创作者画像数据库记录。
 * 与 creator_preference 的区别：preference 是每期任务的快照，
 * 而 profile 是跨任务的用户级聚合画像，从事件和偏好中推理生成。
 */
public class CreatorProfileRecord {

    private Long id;
    private String creatorId;
    private String styleTags;
    private String toneGuide;
    private String audienceView;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(String creatorId) {
        this.creatorId = creatorId;
    }

    public String getStyleTags() {
        return styleTags;
    }

    public void setStyleTags(String styleTags) {
        this.styleTags = styleTags;
    }

    public String getToneGuide() {
        return toneGuide;
    }

    public void setToneGuide(String toneGuide) {
        this.toneGuide = toneGuide;
    }

    public String getAudienceView() {
        return audienceView;
    }

    public void setAudienceView(String audienceView) {
        this.audienceView = audienceView;
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
