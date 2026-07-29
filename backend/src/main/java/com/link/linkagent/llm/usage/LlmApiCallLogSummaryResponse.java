package com.link.linkagent.llm.usage;

/** 全局模型调用日志汇总，数值均基于当前筛选条件。 */
public record LlmApiCallLogSummaryResponse(
        long callCount,
        long successCount,
        long failedCount,
        long skippedCount,
        Long totalTokens,
        Long promptTokens,
        Long completionTokens,
        Long totalElapsedMs,
        Long averageElapsedMs
) {
}
