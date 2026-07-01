package com.link.linkagent.memory.model;

import java.time.LocalDateTime;

/**
 * Agent 执行链路记录。
 * <p>
 * 使用可变 POJO 是因为 MyBatis 注解 SQL 会通过 setter 回填自增主键，同时后续执行过程中会逐步累计 token 和步骤数。
 */
public class AgentTraceRecord {

    private Long id;
    private String traceId;
    private String sessionId;
    private String userInput;
    private String finalOutput;
    private Integer status;
    private Integer totalTokens;
    private Integer totalSteps;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String errorMsg;
    private LocalDateTime createTime;
    private Integer isDeleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserInput() {
        return userInput;
    }

    public void setUserInput(String userInput) {
        this.userInput = userInput;
    }

    public String getFinalOutput() {
        return finalOutput;
    }

    public void setFinalOutput(String finalOutput) {
        this.finalOutput = finalOutput;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Integer getTotalSteps() {
        return totalSteps;
    }

    public void setTotalSteps(Integer totalSteps) {
        this.totalSteps = totalSteps;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public Integer getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Integer isDeleted) {
        this.isDeleted = isDeleted;
    }
}
