package com.link.linkagent.llm.usage;

import java.util.List;

/**
 * 一次创作者工作流会话的模型 API 开销汇总。
 * 这里按 workflow session 聚合，而不是复用任务总览，避免多个工作流会话的调用混在一起。
 */
public record WorkflowUsageResponse(
        String taskId,
        String sessionId,
        long totalCalls,
        long successCalls,
        long failedCalls,
        long skippedCalls,
        Long totalTokens,
        Long totalElapsedMs,
        List<WorkflowStepUsageResponse> steps
) {
}
