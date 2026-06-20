package com.link.linkagent.dto;

import com.link.linkagent.core.AgentExecutionMode;
import com.link.linkagent.core.AgentStep;
import com.link.linkagent.core.multi.AgentWorkerTrace;
import com.link.linkagent.core.plan.AgentPlanTrace;

import java.util.List;

/**
 * Agent 聊天响应，包含最终答案及完整步骤追踪。
 */
public record AgentChatResponse(
        String sessionId,
        String finalAnswer,
        String stopReason,
        int totalSteps,
        List<AgentStep> steps,
        AgentExecutionMode executionMode,
        AgentPlanTrace planTrace,
        List<AgentWorkerTrace> workerTraces
) {

    public AgentChatResponse {
        steps = steps == null ? List.of() : List.copyOf(steps);
        workerTraces = workerTraces == null ? List.of() : List.copyOf(workerTraces);
    }

    public AgentChatResponse(String sessionId, String finalAnswer, String stopReason, int totalSteps,
                             List<AgentStep> steps) {
        this(sessionId, finalAnswer, stopReason, totalSteps, steps, AgentExecutionMode.REACT, null, List.of());
    }
}
