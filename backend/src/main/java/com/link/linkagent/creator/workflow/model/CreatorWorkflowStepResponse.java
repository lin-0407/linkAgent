package com.link.linkagent.creator.workflow.model;

import java.time.LocalDateTime;

/**
 * 工作流步骤返回对象。
 * 这里单独暴露步骤明细，是为了让前端能把“消息展示”和“失败回放”分开看，避免用户误把过程日志当成业务结果。
 */
public record CreatorWorkflowStepResponse(
        Long id,
        String stepId,
        String sessionId,
        String stepType,
        String stepName,
        String status,
        String inputSummary,
        String outputSummary,
        String rawOutput,
        String errorMessage,
        LocalDateTime startTime,
        LocalDateTime endTime,
        LocalDateTime createTime
) {
}
