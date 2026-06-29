package com.link.linkagent.creator.interactive.model;

import java.time.LocalDateTime;

/**
 * 创意卡片数据库记录。
 * 大纲类字段保存 JSON 字符串，便于前端保持原结构展示，也避免过早拆子表。
 */
public class CreativeIdeaOptionRecord {

    private Long id;
    private String optionId;
    private String sessionId;
    private String taskId;
    private String optionName;
    private String targetAudience;
    private String titleOutline;
    private String contentOutline;
    private String descriptionOutline;
    private String sellingPoints;
    private String riskPoints;
    private String recommendReason;
    private Boolean selected;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOptionId() {
        return optionId;
    }

    public void setOptionId(String optionId) {
        this.optionId = optionId;
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

    public String getOptionName() {
        return optionName;
    }

    public void setOptionName(String optionName) {
        this.optionName = optionName;
    }

    public String getTargetAudience() {
        return targetAudience;
    }

    public void setTargetAudience(String targetAudience) {
        this.targetAudience = targetAudience;
    }

    public String getTitleOutline() {
        return titleOutline;
    }

    public void setTitleOutline(String titleOutline) {
        this.titleOutline = titleOutline;
    }

    public String getContentOutline() {
        return contentOutline;
    }

    public void setContentOutline(String contentOutline) {
        this.contentOutline = contentOutline;
    }

    public String getDescriptionOutline() {
        return descriptionOutline;
    }

    public void setDescriptionOutline(String descriptionOutline) {
        this.descriptionOutline = descriptionOutline;
    }

    public String getSellingPoints() {
        return sellingPoints;
    }

    public void setSellingPoints(String sellingPoints) {
        this.sellingPoints = sellingPoints;
    }

    public String getRiskPoints() {
        return riskPoints;
    }

    public void setRiskPoints(String riskPoints) {
        this.riskPoints = riskPoints;
    }

    public String getRecommendReason() {
        return recommendReason;
    }

    public void setRecommendReason(String recommendReason) {
        this.recommendReason = recommendReason;
    }

    public Boolean getSelected() {
        return selected;
    }

    public void setSelected(Boolean selected) {
        this.selected = selected;
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
