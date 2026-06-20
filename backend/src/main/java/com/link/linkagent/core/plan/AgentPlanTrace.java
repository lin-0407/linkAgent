package com.link.linkagent.core.plan;

import java.util.List;

/**
 * 对外展示的计划轨迹。
 */
public record AgentPlanTrace(
        String objective,
        String rationale,
        String coverageCheck,
        List<AgentPlanStep> plannedSteps,
        List<PlanStepExecution> executions
) {

    public AgentPlanTrace {
        plannedSteps = plannedSteps == null ? List.of() : List.copyOf(plannedSteps);
        executions = executions == null ? List.of() : List.copyOf(executions);
    }

    public static AgentPlanTrace from(AgentPlan plan, List<PlanStepExecution> executions) {
        if (plan == null) {
            return null;
        }
        return new AgentPlanTrace(
                plan.objective(),
                plan.rationale(),
                plan.coverageCheck(),
                plan.steps(),
                executions
        );
    }
}
