package com.link.linkagent.creator.feedback.model;

import java.time.LocalDateTime;

/**
 * 评论弹幕分析报告数据库记录。
 * 列表型字段先保存为 JSON 字符串，避免 MVP 阶段拆出过多子表。
 */
public class CreatorFeedbackReportRecord {

    private Long id;
    private String reportId;
    private String taskId;
    private String feedbackSummary;
    private String hotTopics;
    private String sentimentSummary;
    private String controversyPoints;
    private String misunderstandingPoints;
    private String nextContentSuggestions;
    private String interactionSuggestions;
    // 阶段 4.12 新增的高信号字段：回答“为什么这样反馈”和“下一步怎么改”，和旧总结字段并存。
    private String creatorFeedbackDilemma;
    private String audienceCoreConcern;
    private String misunderstandingSourceAnalysis;
    private String feedbackActionPlan;
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

    public String getFeedbackSummary() {
        return feedbackSummary;
    }

    public void setFeedbackSummary(String feedbackSummary) {
        this.feedbackSummary = feedbackSummary;
    }

    public String getHotTopics() {
        return hotTopics;
    }

    public void setHotTopics(String hotTopics) {
        this.hotTopics = hotTopics;
    }

    public String getSentimentSummary() {
        return sentimentSummary;
    }

    public void setSentimentSummary(String sentimentSummary) {
        this.sentimentSummary = sentimentSummary;
    }

    public String getControversyPoints() {
        return controversyPoints;
    }

    public void setControversyPoints(String controversyPoints) {
        this.controversyPoints = controversyPoints;
    }

    public String getMisunderstandingPoints() {
        return misunderstandingPoints;
    }

    public void setMisunderstandingPoints(String misunderstandingPoints) {
        this.misunderstandingPoints = misunderstandingPoints;
    }

    public String getNextContentSuggestions() {
        return nextContentSuggestions;
    }

    public void setNextContentSuggestions(String nextContentSuggestions) {
        this.nextContentSuggestions = nextContentSuggestions;
    }

    public String getInteractionSuggestions() {
        return interactionSuggestions;
    }

    public void setInteractionSuggestions(String interactionSuggestions) {
        this.interactionSuggestions = interactionSuggestions;
    }

    public String getCreatorFeedbackDilemma() {
        return creatorFeedbackDilemma;
    }

    public void setCreatorFeedbackDilemma(String creatorFeedbackDilemma) {
        this.creatorFeedbackDilemma = creatorFeedbackDilemma;
    }

    public String getAudienceCoreConcern() {
        return audienceCoreConcern;
    }

    public void setAudienceCoreConcern(String audienceCoreConcern) {
        this.audienceCoreConcern = audienceCoreConcern;
    }

    public String getMisunderstandingSourceAnalysis() {
        return misunderstandingSourceAnalysis;
    }

    public void setMisunderstandingSourceAnalysis(String misunderstandingSourceAnalysis) {
        this.misunderstandingSourceAnalysis = misunderstandingSourceAnalysis;
    }

    public String getFeedbackActionPlan() {
        return feedbackActionPlan;
    }

    public void setFeedbackActionPlan(String feedbackActionPlan) {
        this.feedbackActionPlan = feedbackActionPlan;
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
