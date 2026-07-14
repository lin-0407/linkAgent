package com.link.linkagent.creator.bilibili.model;

import java.time.LocalDateTime;

/**
 * 视频分析报告表（creator_video_analysis_report）数据库记录。
 * 使用可写 JavaBean，保证 MyBatis 的注解结果映射可以稳定回填所有分析字段。
 */
public class VideoAnalysisReportRecord {

    private Long id;
    private String analysisId;
    private String taskId;
    private String bvid;
    private String workflowSessionId;
    private String analysisStatus;
    private String oneSentenceSummary;
    private String publishPlanReview;
    private String audienceFocus;
    private String misunderstandingPoints;
    private String controversyPoints;
    private String nextActionPlan;
    private String evidenceSummary;
    private String rawOutput;
    private String parseStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** MyBatis 查询时先创建空对象，再逐字段调用 setter。 */
    public VideoAnalysisReportRecord() {
    }

    /** 业务保存完整分析结果时使用全参构造，避免遗漏关联任务和解析状态。 */
    public VideoAnalysisReportRecord(Long id, String analysisId, String taskId, String bvid,
                                     String workflowSessionId, String analysisStatus,
                                     String oneSentenceSummary, String publishPlanReview,
                                     String audienceFocus, String misunderstandingPoints,
                                     String controversyPoints, String nextActionPlan,
                                     String evidenceSummary, String rawOutput, String parseStatus,
                                     LocalDateTime createTime, LocalDateTime updateTime) {
        this.id = id;
        this.analysisId = analysisId;
        this.taskId = taskId;
        this.bvid = bvid;
        this.workflowSessionId = workflowSessionId;
        this.analysisStatus = analysisStatus;
        this.oneSentenceSummary = oneSentenceSummary;
        this.publishPlanReview = publishPlanReview;
        this.audienceFocus = audienceFocus;
        this.misunderstandingPoints = misunderstandingPoints;
        this.controversyPoints = controversyPoints;
        this.nextActionPlan = nextActionPlan;
        this.evidenceSummary = evidenceSummary;
        this.rawOutput = rawOutput;
        this.parseStatus = parseStatus;
        this.createTime = createTime;
        this.updateTime = updateTime;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAnalysisId() { return analysisId; }
    public void setAnalysisId(String analysisId) { this.analysisId = analysisId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getBvid() { return bvid; }
    public void setBvid(String bvid) { this.bvid = bvid; }
    public String getWorkflowSessionId() { return workflowSessionId; }
    public void setWorkflowSessionId(String workflowSessionId) { this.workflowSessionId = workflowSessionId; }
    public String getAnalysisStatus() { return analysisStatus; }
    public void setAnalysisStatus(String analysisStatus) { this.analysisStatus = analysisStatus; }
    public String getOneSentenceSummary() { return oneSentenceSummary; }
    public void setOneSentenceSummary(String oneSentenceSummary) { this.oneSentenceSummary = oneSentenceSummary; }
    public String getPublishPlanReview() { return publishPlanReview; }
    public void setPublishPlanReview(String publishPlanReview) { this.publishPlanReview = publishPlanReview; }
    public String getAudienceFocus() { return audienceFocus; }
    public void setAudienceFocus(String audienceFocus) { this.audienceFocus = audienceFocus; }
    public String getMisunderstandingPoints() { return misunderstandingPoints; }
    public void setMisunderstandingPoints(String misunderstandingPoints) { this.misunderstandingPoints = misunderstandingPoints; }
    public String getControversyPoints() { return controversyPoints; }
    public void setControversyPoints(String controversyPoints) { this.controversyPoints = controversyPoints; }
    public String getNextActionPlan() { return nextActionPlan; }
    public void setNextActionPlan(String nextActionPlan) { this.nextActionPlan = nextActionPlan; }
    public String getEvidenceSummary() { return evidenceSummary; }
    public void setEvidenceSummary(String evidenceSummary) { this.evidenceSummary = evidenceSummary; }
    public String getRawOutput() { return rawOutput; }
    public void setRawOutput(String rawOutput) { this.rawOutput = rawOutput; }
    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String parseStatus) { this.parseStatus = parseStatus; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
