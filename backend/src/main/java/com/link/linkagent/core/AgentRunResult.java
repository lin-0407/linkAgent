package com.link.linkagent.core;

import com.link.linkagent.core.multi.AgentWorkerTrace;
import com.link.linkagent.core.plan.AgentPlanTrace;

import java.util.List;

/**
 * Agent 内核内部统一结果。
 * <p>
 * ReAct、Plan-and-Execute、多 Agent 三条路径都先汇总成这个对象，再由 Controller 响应 DTO 对外返回。
 * 这样可以保留旧字段兼容，同时逐步增加计划轨迹和 Worker 轨迹。
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

    public static AgentRunResult react(String finalAnswer, String stopReason, List<AgentStep> steps) {
        return new AgentRunResult(AgentExecutionMode.REACT, finalAnswer, stopReason, steps, null, List.of());
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
