package com.link.linkagent.creator.profile.model;

import java.time.LocalDateTime;

/**
 * 创作者事件流水数据库记录。
 * 与 creator_workflow_message 的区别：workflow_message 记录对话消息，
 * 而本表记录用户对 AI 建议的"采纳/拒绝/修改"等业务动作，是画像更新的信号源。
 */
public class CreatorEventRecord {

    private Long id;
    private String eventId;
    private String creatorId;
    private String eventType;
    private String taskId;
    private String payload;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(String creatorId) {
        this.creatorId = creatorId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
