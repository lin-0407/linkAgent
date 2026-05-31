package com.link.linkagent.creator.evaluation.model;

import java.time.LocalDateTime;

/**
 * 评测用例数据库记录。
 * 评测样例需要保留到数据库里，后续才能稳定做人工评分和失败回放，而不是停留在一次性的页面输入中。
 */
public class CreatorEvalCaseRecord {

    private Long id;
    private String caseId;
    private String userId;
    private String caseName;
    private String targetStage;
    private String taskId;
    private String inputSnapshot;
    private String expectedPoints;
    private String scoringRubric;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCaseName() {
        return caseName;
    }

    public void setCaseName(String caseName) {
        this.caseName = caseName;
    }

    public String getTargetStage() {
        return targetStage;
    }

    public void setTargetStage(String targetStage) {
        this.targetStage = targetStage;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getInputSnapshot() {
        return inputSnapshot;
    }

    public void setInputSnapshot(String inputSnapshot) {
        this.inputSnapshot = inputSnapshot;
    }

    public String getExpectedPoints() {
        return expectedPoints;
    }

    public void setExpectedPoints(String expectedPoints) {
        this.expectedPoints = expectedPoints;
    }

    public String getScoringRubric() {
        return scoringRubric;
    }

    public void setScoringRubric(String scoringRubric) {
        this.scoringRubric = scoringRubric;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
