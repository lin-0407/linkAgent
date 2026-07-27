package com.link.linkagent.creator.workflow.model;

import java.time.LocalDateTime;

/**
 * 工作流会话数据库记录。
 * 会话是任务级消息流的边界，后续 SSE 和失败回放都应该挂在这个 sessionId 下。
 */
public class CreatorWorkflowSessionRecord {

    private Long id;
    private String sessionId;
    private String taskId;
    private String stage;
    private String status;
    private String userId;
    private String confirmedResultId;
    private String planContextHash;
    private Integer planGenerationCount;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getConfirmedResultId() {
        return confirmedResultId;
    }

    public void setConfirmedResultId(String confirmedResultId) {
        this.confirmedResultId = confirmedResultId;
    }

    public String getPlanContextHash() {
        return planContextHash;
    }

    public void setPlanContextHash(String planContextHash) {
        this.planContextHash = planContextHash;
    }

    public Integer getPlanGenerationCount() {
        return planGenerationCount;
    }

    public void setPlanGenerationCount(Integer planGenerationCount) {
        this.planGenerationCount = planGenerationCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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
