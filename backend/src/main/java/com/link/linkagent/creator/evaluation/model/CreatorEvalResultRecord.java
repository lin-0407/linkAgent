package com.link.linkagent.creator.evaluation.model;

import java.time.LocalDateTime;

/**
 * 评测结果数据库记录。
 * 结果记录必须和原始输出、耗时、失败原因一起保存，这样后面做回放时才能定位问题来源而不是只看最终分数。
 */
public class CreatorEvalResultRecord {

    private Long id;
    private String resultId;
    private String caseId;
    private String taskId;
    private String workflowSessionId;
    private String targetStage;
    private String modelName;
    private String promptVersion;
    private String promptHash;
    private String promptSnapshot;
    private String outputSummary;
    private String rawOutput;
    private String runStatus;
    private String parseStatus;
    private Long elapsedMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private String failureReason;
    private Integer readabilityScore;
    private Integer relevanceScore;
    private Integer completenessScore;
    private Integer accuracyScore;
    private Integer stabilityScore;
    private Integer costScore;
    private Integer explainabilityScore;
    private String reviewerNote;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getResultId() {
        return resultId;
    }

    public void setResultId(String resultId) {
        this.resultId = resultId;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getWorkflowSessionId() {
        return workflowSessionId;
    }

    public void setWorkflowSessionId(String workflowSessionId) {
        this.workflowSessionId = workflowSessionId;
    }

    public String getTargetStage() {
        return targetStage;
    }

    public void setTargetStage(String targetStage) {
        this.targetStage = targetStage;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getPromptHash() {
        return promptHash;
    }

    public void setPromptHash(String promptHash) {
        this.promptHash = promptHash;
    }

    public String getPromptSnapshot() {
        return promptSnapshot;
    }

    public void setPromptSnapshot(String promptSnapshot) {
        this.promptSnapshot = promptSnapshot;
    }

    public String getOutputSummary() {
        return outputSummary;
    }

    public void setOutputSummary(String outputSummary) {
        this.outputSummary = outputSummary;
    }

    public String getRawOutput() {
        return rawOutput;
    }

    public void setRawOutput(String rawOutput) {
        this.rawOutput = rawOutput;
    }

    public String getRunStatus() {
        return runStatus;
    }

    public void setRunStatus(String runStatus) {
        this.runStatus = runStatus;
    }

    public String getParseStatus() {
        return parseStatus;
    }

    public void setParseStatus(String parseStatus) {
        this.parseStatus = parseStatus;
    }

    public Long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(Long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Integer getReadabilityScore() {
        return readabilityScore;
    }

    public void setReadabilityScore(Integer readabilityScore) {
        this.readabilityScore = readabilityScore;
    }

    public Integer getRelevanceScore() {
        return relevanceScore;
    }

    public void setRelevanceScore(Integer relevanceScore) {
        this.relevanceScore = relevanceScore;
    }

    public Integer getCompletenessScore() {
        return completenessScore;
    }

    public void setCompletenessScore(Integer completenessScore) {
        this.completenessScore = completenessScore;
    }

    public Integer getAccuracyScore() {
        return accuracyScore;
    }

    public void setAccuracyScore(Integer accuracyScore) {
        this.accuracyScore = accuracyScore;
    }

    public Integer getStabilityScore() {
        return stabilityScore;
    }

    public void setStabilityScore(Integer stabilityScore) {
        this.stabilityScore = stabilityScore;
    }

    public Integer getCostScore() {
        return costScore;
    }

    public void setCostScore(Integer costScore) {
        this.costScore = costScore;
    }

    public Integer getExplainabilityScore() {
        return explainabilityScore;
    }

    public void setExplainabilityScore(Integer explainabilityScore) {
        this.explainabilityScore = explainabilityScore;
    }

    public String getReviewerNote() {
        return reviewerNote;
    }

    public void setReviewerNote(String reviewerNote) {
        this.reviewerNote = reviewerNote;
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
