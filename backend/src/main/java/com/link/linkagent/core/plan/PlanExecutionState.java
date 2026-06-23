package com.link.linkagent.core.plan;

import java.util.List;

/**
 * PaE 执行现场。
 * <p>
 * Replanner 只需要看已发生的事实、还没执行的步骤和失败指纹，避免重新改写已经成功的步骤。
 */
record PlanExecutionState(
        String objective,
        List<PlanStepExecution> executedSteps,
        List<AgentPlanStep> remainingSteps,
        List<String> failedFingerprints
) {

    PlanExecutionState {
        executedSteps = executedSteps == null ? List.of() : List.copyOf(executedSteps);
        remainingSteps = remainingSteps == null ? List.of() : List.copyOf(remainingSteps);
        failedFingerprints = failedFingerprints == null ? List.of() : List.copyOf(failedFingerprints);
    }
}