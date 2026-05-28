package com.link.linkagent.creator.report.model;

import java.time.LocalDateTime;

/**
 * 创作复盘报告数据库记录。
 * 列表和对象型字段先保存为 JSON 字符串，避免 MVP 阶段拆出过多明细表。
 */
public class CreatorReportRecord {

    private Long id;
    private String reportId;
    private String taskId;
    private String contentSummary;
    private String coreSellingPoints;
    private String titleDescriptionReview;
    private String audienceFeedbackSummary;
    private String competitorComparison;
    private String controversyAndMisunderstanding;
    private String nextActionSuggestions;
    private String creatorPreferenceInsight;
    private String overallConclusion;
    private String rawOutput;
    private String parseStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getContentSummary() {
        return contentSummary;
    }

    public void setContentSummary(String contentSummary) {
        this.contentSummary = contentSummary;
    }

    public String getCoreSellingPoints() {
        return coreSellingPoints;
    }

    public void setCoreSellingPoints(String coreSellingPoints) {
        this.coreSellingPoints = coreSellingPoints;
    }

    public String getTitleDescriptionReview() {
        return titleDescriptionReview;
    }

    public void setTitleDescriptionReview(String titleDescriptionReview) {
        this.titleDescriptionReview = titleDescriptionReview;
    }

    public String getAudienceFeedbackSummary() {
        return audienceFeedbackSummary;
    }

    public void setAudienceFeedbackSummary(String audienceFeedbackSummary) {
        this.audienceFeedbackSummary = audienceFeedbackSummary;
    }

    public String getCompetitorComparison() {
        return competitorComparison;
    }

    public void setCompetitorComparison(String competitorComparison) {
        this.competitorComparison = competitorComparison;
    }

    public String getControversyAndMisunderstanding() {
        return controversyAndMisunderstanding;
    }

    public void setControversyAndMisunderstanding(String controversyAndMisunderstanding) {
        this.controversyAndMisunderstanding = controversyAndMisunderstanding;
    }

    public String getNextActionSuggestions() {
        return nextActionSuggestions;
    }

    public void setNextActionSuggestions(String nextActionSuggestions) {
        this.nextActionSuggestions = nextActionSuggestions;
    }

    public String getCreatorPreferenceInsight() {
        return creatorPreferenceInsight;
    }

    public void setCreatorPreferenceInsight(String creatorPreferenceInsight) {
        this.creatorPreferenceInsight = creatorPreferenceInsight;
    }

    public String getOverallConclusion() {
        return overallConclusion;
    }

    public void setOverallConclusion(String overallConclusion) {
        this.overallConclusion = overallConclusion;
    }

    public String getRawOutput() {
        return rawOutput;
    }

    public void setRawOutput(String rawOutput) {
        this.rawOutput = rawOutput;
    }

    public String getParseStatus() {
        return parseStatus;
    }

    public void setParseStatus(String parseStatus) {
        this.parseStatus = parseStatus;
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
