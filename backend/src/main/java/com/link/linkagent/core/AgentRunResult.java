package com.link.linkagent.core;

import com.link.linkagent.core.multi.AgentWorkerTrace;
import com.link.linkagent.core.plan.AgentPlanTrace;

import java.util.List;

/**
 * Agent 内核内部统一结果。
 * <p>
 * Plan-and-Execute 与多 Agent 路径先汇总成这个对象，再转换为统一响应 DTO。
 * ReAct 仍直接生成响应，不经过这层规划结果模型。
 */
public record AgentRunResult(
        AgentExecutionMode executionMode,
        String finalAnswer,
        String stopReason,
        List<AgentStep> steps,
        AgentPlanTrace planTrace,
        List<AgentWorkerTrace> workerTraces
) {

    public AgentRunResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        workerTraces = workerTraces == null ? List.of() : List.copyOf(workerTraces);
    }

    public static AgentRunResult planExecute(String finalAnswer, String stopReason, List<AgentStep> steps,
                                             AgentPlanTrace planTrace) {
        return new AgentRunResult(AgentExecutionMode.PLAN_EXECUTE, finalAnswer, stopReason, steps, planTrace, List.of());
    }

    public static AgentRunResult multiAgent(String finalAnswer, String stopReason, List<AgentStep> steps,
                                            AgentPlanTrace planTrace, List<AgentWorkerTrace> workerTraces) {
        return new AgentRunResult(AgentExecutionMode.MULTI_AGENT, finalAnswer, stopReason, steps, planTrace, workerTraces);
    }

    public int totalSteps() {
        return steps.size();
    }
}
