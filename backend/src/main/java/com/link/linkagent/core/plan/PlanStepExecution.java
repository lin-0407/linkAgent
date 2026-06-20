package com.link.linkagent.core.plan;

import java.util.List;

/**
 * 计划步骤执行回放。
 * <p>
 * 前端展示计划轨迹时不直接复用 AgentStep，是因为 AgentStep 只描述 ReAct 的 Thought/Action/Observation，
 * 而 PaE 还需要展示计划依赖、预期结果和执行状态。
 */
public record PlanStepExecution(
        int stepId,
        String description,
        String action,
        String actionInput,
        List<Integer> dependsOn,
        String expectedObservation,
        PlanStepStatus status,
        String observation,
        String errorMessage
) {

    public PlanStepExecution {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
