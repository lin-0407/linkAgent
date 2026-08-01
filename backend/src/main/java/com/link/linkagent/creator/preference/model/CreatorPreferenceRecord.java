package com.link.linkagent.creator.preference.model;

import java.time.LocalDateTime;

/**
 * 创作者长期偏好数据库记录。
 * 每个任务保留一份复盘快照，是为了让后续发布前优化能够读取多期历史，而不是只看到最后一次覆盖结果。
 */
public class CreatorPreferenceRecord {

    private Long id;
    private String preferenceId;
    private String userId;
    private String sourceTaskId;
    private String sourceTaskName;
    private String sourceReportId;
    private String preferenceContent;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPreferenceId() {
        return preferenceId;
    }

    public void setPreferenceId(String preferenceId) {
        this.preferenceId = preferenceId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSourceTaskId() {
        return sourceTaskId;
    }

    public void setSourceTaskId(String sourceTaskId) {
        this.sourceTaskId = sourceTaskId;
    }

    public String getSourceTaskName() {
        return sourceTaskName;
    }

    public void setSourceTaskName(String sourceTaskName) {
        this.sourceTaskName = sourceTaskName;
    }

    public String getSourceReportId() {
        return sourceReportId;
    }

    public void setSourceReportId(String sourceReportId) {
        this.sourceReportId = sourceReportId;
    }

    public String getPreferenceContent() {
        return preferenceContent;
    }

    public void setPreferenceContent(String preferenceContent) {
        this.preferenceContent = preferenceContent;
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
