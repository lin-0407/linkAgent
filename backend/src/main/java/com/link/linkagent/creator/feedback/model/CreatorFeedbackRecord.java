package com.link.linkagent.creator.feedback.model;

import java.time.LocalDateTime;

/**
 * 评论弹幕样例数据库记录。
 * 每个任务先保留一份最新样例，便于 MVP 阶段快速演示反馈分析闭环。
 */
public class CreatorFeedbackRecord {

    private Long id;
    private String feedbackId;
    private String taskId;
    private String commentSamples;
    private String danmakuSamples;
    private String extraContext;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFeedbackId() {
        return feedbackId;
    }

    public void setFeedbackId(String feedbackId) {
        this.feedbackId = feedbackId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getCommentSamples() {
        return commentSamples;
    }

    public void setCommentSamples(String commentSamples) {
        this.commentSamples = commentSamples;
    }

    public String getDanmakuSamples() {
        return danmakuSamples;
    }

    public void setDanmakuSamples(String danmakuSamples) {
        this.danmakuSamples = danmakuSamples;
    }

    public String getExtraContext() {
        return extraContext;
    }

    public void setExtraContext(String extraContext) {
        this.extraContext = extraContext;
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
