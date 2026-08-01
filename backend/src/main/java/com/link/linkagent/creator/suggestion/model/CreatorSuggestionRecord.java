package com.link.linkagent.creator.suggestion.model;

import java.time.LocalDateTime;

/**
 * 发布前优化建议数据库记录。
 * 列表字段先以 JSON 字符串保存，是为了避免在 MVP 阶段引入复杂子表。
 */
public class CreatorSuggestionRecord {

    private Long id;
    private String suggestionId;
    private String taskId;
    private String sessionId;
    private String contentSummary;
    private String creatorDilemma;
    private String audienceProfile;
    private String audienceHook;
    private String contentPositioning;
    private String sellingPoints;
    private String riskPoints;
    private String titleSuggestions;
    private String descriptionSuggestion;
    private String actionableRevisionPlan;
    private String tagSuggestions;
    private String partitionSuggestion;
    private String evidenceRefs;
    private String missingInfo;
    private String generationMode;
    private String qualityStatus;
    private String auditReport;
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

    public String getSuggestionId() {
        return suggestionId;
    }

    public void setSuggestionId(String suggestionId) {
        this.suggestionId = suggestionId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getContentSummary() {
        return contentSummary;
    }

    public void setContentSummary(String contentSummary) {
        this.contentSummary = contentSummary;
    }

    public String getCreatorDilemma() {
        return creatorDilemma;
    }

    public void setCreatorDilemma(String creatorDilemma) {
        this.creatorDilemma = creatorDilemma;
    }

    public String getAudienceProfile() {
        return audienceProfile;
    }

    public void setAudienceProfile(String audienceProfile) {
        this.audienceProfile = audienceProfile;
    }

    public String getAudienceHook() {
        return audienceHook;
    }

    public void setAudienceHook(String audienceHook) {
        this.audienceHook = audienceHook;
    }

    public String getContentPositioning() {
        return contentPositioning;
    }

    public void setContentPositioning(String contentPositioning) {
        this.contentPositioning = contentPositioning;
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

    public String getTitleSuggestions() {
        return titleSuggestions;
    }

    public void setTitleSuggestions(String titleSuggestions) {
        this.titleSuggestions = titleSuggestions;
    }

    public String getDescriptionSuggestion() {
        return descriptionSuggestion;
    }

    public void setDescriptionSuggestion(String descriptionSuggestion) {
        this.descriptionSuggestion = descriptionSuggestion;
    }

    public String getActionableRevisionPlan() {
        return actionableRevisionPlan;
    }

    public void setActionableRevisionPlan(String actionableRevisionPlan) {
        this.actionableRevisionPlan = actionableRevisionPlan;
    }

    public String getTagSuggestions() {
        return tagSuggestions;
    }

    public void setTagSuggestions(String tagSuggestions) {
        this.tagSuggestions = tagSuggestions;
    }

    public String getPartitionSuggestion() {
        return partitionSuggestion;
    }

    public void setPartitionSuggestion(String partitionSuggestion) {
        this.partitionSuggestion = partitionSuggestion;
    }

    public String getEvidenceRefs() {
        return evidenceRefs;
    }

    public void setEvidenceRefs(String evidenceRefs) {
        this.evidenceRefs = evidenceRefs;
    }

    public String getMissingInfo() {
        return missingInfo;
    }

    public void setMissingInfo(String missingInfo) {
        this.missingInfo = missingInfo;
    }

    public String getGenerationMode() {
        return generationMode;
    }

    public void setGenerationMode(String generationMode) {
        this.generationMode = generationMode;
    }

    public String getQualityStatus() {
        return qualityStatus;
    }

    public void setQualityStatus(String qualityStatus) {
        this.qualityStatus = qualityStatus;
    }

    public String getAuditReport() {
        return auditReport;
    }

    public void setAuditReport(String auditReport) {
        this.auditReport = auditReport;
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
