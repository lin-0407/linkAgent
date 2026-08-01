package com.link.linkagent.creator.task.model;

import java.time.LocalDateTime;

/**
 * 任务级发布前设置数据库记录。
 * 使用 JavaBean 贴合项目现有 MyBatis 映射方式，避免依赖构造参数名推断。
 */
public class PrePublishSettingsRecord {

    private Long id;
    private String taskId;
    private String preferenceMode;
    private String creatorPreference;
    private String titleStyle;
    private String extraRequirement;
    private String customGuidance;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public String getTaskId() { return taskId; }

    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getPreferenceMode() { return preferenceMode; }

    public void setPreferenceMode(String preferenceMode) { this.preferenceMode = preferenceMode; }

    public String getCreatorPreference() { return creatorPreference; }

    public void setCreatorPreference(String creatorPreference) { this.creatorPreference = creatorPreference; }

    public String getTitleStyle() { return titleStyle; }

    public void setTitleStyle(String titleStyle) { this.titleStyle = titleStyle; }

    public String getExtraRequirement() { return extraRequirement; }

    public void setExtraRequirement(String extraRequirement) { this.extraRequirement = extraRequirement; }

    public String getCustomGuidance() { return customGuidance; }

    public void setCustomGuidance(String customGuidance) { this.customGuidance = customGuidance; }

    public LocalDateTime getCreateTime() { return createTime; }

    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }

    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
