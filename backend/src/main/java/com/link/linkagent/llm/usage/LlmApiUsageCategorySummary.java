package com.link.linkagent.llm.usage;

/**
 * 单个模型分类的聚合统计。
 * 用 Java Bean 承接 MyBatis 聚合结果，避免 record 构造映射在不同 MyBatis 版本下出现兼容性问题。
 */
public class LlmApiUsageCategorySummary {

    private String modelCategory;
    private long callCount;
    private long successCount;
    private long failedCount;
    private long skippedCount;
    private Long totalTokens;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalElapsedMs;
    private Long averageElapsedMs;

    public String getModelCategory() {
        return modelCategory;
    }

    public void setModelCategory(String modelCategory) {
        this.modelCategory = modelCategory;
    }

    public long getCallCount() {
        return callCount;
    }

    public void setCallCount(long callCount) {
        this.callCount = callCount;
    }

    public long getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(long successCount) {
        this.successCount = successCount;
    }

    public long getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(long failedCount) {
        this.failedCount = failedCount;
    }

    public long getSkippedCount() {
        return skippedCount;
    }

    public void setSkippedCount(long skippedCount) {
        this.skippedCount = skippedCount;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Long getTotalElapsedMs() {
        return totalElapsedMs;
    }

    public void setTotalElapsedMs(Long totalElapsedMs) {
        this.totalElapsedMs = totalElapsedMs;
    }

    public Long getAverageElapsedMs() {
        return averageElapsedMs;
    }

    public void setAverageElapsedMs(Long averageElapsedMs) {
        this.averageElapsedMs = averageElapsedMs;
    }
}
