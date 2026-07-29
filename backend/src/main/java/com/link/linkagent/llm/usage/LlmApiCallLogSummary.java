package com.link.linkagent.llm.usage;

/**
 * 全局模型调用日志的聚合查询结果。
 * 使用可变 Bean 是为了让 MyBatis 能稳定映射聚合列，接口层再转换为只读响应。
 */
public class LlmApiCallLogSummary {

    private long callCount;
    private long successCount;
    private long failedCount;
    private long skippedCount;
    private Long totalTokens;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalElapsedMs;

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
}
