package com.link.linkagent.creator.competitor.model;

import java.time.LocalDateTime;

/**
 * 竞品分析报告数据库记录。
 * 对比结论先用 JSON 字符串保存，避免 MVP 阶段拆出复杂明细表。
 */
public class CreatorCompetitorReportRecord {

    private Long id;
    private String reportId;
    private String taskId;
    private String competitorSummary;
    private String competitorAdvantages;
    private String ownAdvantages;
    private String ownDisadvantages;
    private String gapAnalysis;
    private String improvementSuggestions;
    private String differentiationStrategy;
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

    public String getCompetitorSummary() {
        return competitorSummary;
    }

    public void setCompetitorSummary(String competitorSummary) {
        this.competitorSummary = competitorSummary;
    }

    public String getCompetitorAdvantages() {
        return competitorAdvantages;
    }

    public void setCompetitorAdvantages(String competitorAdvantages) {
        this.competitorAdvantages = competitorAdvantages;
    }

    public String getOwnAdvantages() {
        return ownAdvantages;
    }

    public void setOwnAdvantages(String ownAdvantages) {
        this.ownAdvantages = ownAdvantages;
    }

    public String getOwnDisadvantages() {
        return ownDisadvantages;
    }

    public void setOwnDisadvantages(String ownDisadvantages) {
        this.ownDisadvantages = ownDisadvantages;
    }

    public String getGapAnalysis() {
        return gapAnalysis;
    }

    public void setGapAnalysis(String gapAnalysis) {
        this.gapAnalysis = gapAnalysis;
    }

    public String getImprovementSuggestions() {
        return improvementSuggestions;
    }

    public void setImprovementSuggestions(String improvementSuggestions) {
        this.improvementSuggestions = improvementSuggestions;
    }

    public String getDifferentiationStrategy() {
        return differentiationStrategy;
    }

    public void setDifferentiationStrategy(String differentiationStrategy) {
        this.differentiationStrategy = differentiationStrategy;
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
