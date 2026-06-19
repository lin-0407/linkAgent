package com.link.linkagent.llm.usage;

import java.util.List;

/**
 * 当前任务的模型调用开销总览。
 * taskId 放在响应里，是为了前端切换任务时能明确当前数据对应哪一个任务。
 */
public record LlmApiUsageSummaryResponse(
        String taskId,
        long callCount,
        long successCount,
        long failedCount,
        long skippedCount,
        Long totalTokens,
        Long totalElapsedMs,
        Long averageElapsedMs,
        List<LlmApiUsageCategorySummary> categories
) {
}
