package com.link.linkagent.creator.task.model;

import java.time.LocalDateTime;

/**
 * 创作任务列表查询记录。
 * 单独定义查询记录，是为了避免 MyBatis 直接构造 response record 时受编译参数影响。
 */
public class CreatorTaskSummaryRecord {

    private Long id;
    private String taskId;
    private String userId;
    private String taskName;
    private String videoType;
    private String status;
    private Integer materialCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getVideoType() {
        return videoType;
    }

    public void setVideoType(String videoType) {
        this.videoType = videoType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getMaterialCount() {
        return materialCount;
    }

    public void setMaterialCount(Integer materialCount) {
        this.materialCount = materialCount;
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
