package com.link.linkagent.core.plan;

import com.link.linkagent.core.AgentRunResult;
import com.link.linkagent.core.AgentStep;
import com.link.linkagent.core.Observation;
import com.link.linkagent.core.ToolCall;
import com.link.linkagent.tool.ToolExecutor;
import com.link.linkagent.tool.ToolRegistry;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plan-and-Execute Agent。
 * <p>
 * 这里的核心不是再写一个 ReAct 循环，而是把“先整体规划、再按计划执行、最后合成回答”变成显式流程。
 * 工具执行仍走现有 ToolExecutor，因此工具生态不会分裂。
 */
@Component
public class PlanAndExecuteAgent {

    private static final int MAX_REPLAN_ATTEMPTS = 2;

    private final AgentPlanner agentPlanner;
    private final AgentReplanner agentReplanner;
    private final ToolExecutor toolExecutor;
    private final ToolRegistry toolRegistry;
    private final AgentAnswerSynthesizer answerSynthesizer;

    public PlanAndExecuteAgent(AgentPlanner agentPlanner,
                               AgentReplanner agentReplanner,
                               ToolExecutor toolExecutor,
                               ToolRegistry toolRegistry,
                               AgentAnswerSynthesizer answerSynthesizer) {
        this.agentPlanner = agentPlanner;
        this.agentReplanner = agentReplanner;
        this.toolExecutor = toolExecutor;
        this.toolRegistry = toolRegistry;
        this.answerSynthesizer = answerSynthesizer;
    }

    public AgentRunResult run(String conversationContext, String userMessage) {
        AgentPlan plan = agentPlanner.plan(conversationContext, userMessage);
        PlanExecutionResult executionResult = executePlan(plan, conversationContext, userMessage);
        List<PlanStepExecution> executions = executionResult.executions();
        List<AgentStep> steps = toAgentSteps(executions);
        AgentPlanTrace trace = AgentPlanTrace.from(plan, executions);
        String stopReason = resolveStopReason(plan, executionResult);
        String finalAnswer = answerSynthesizer.synthesizePlanResult(conversationContext, userMessage, plan, executions);
        return AgentRunResult.planExecute(finalAnswer, stopReason, steps, trace);
    }

    private PlanExecutionResult executePlan(AgentPlan plan, String conversationContext, String userMessage) {
        List<PlanStepExecution> executions = new ArrayList<>();
        List<Integer> recoveredFailureStepIds = new ArrayList<>();
        Set<Integer> successStepIds = new HashSet<>();
        Set<String> failedFingerprints = new HashSet<>();
        int replanAttempts = 0;
        if (plan == null || plan.steps().isEmpty()) {
            return new PlanExecutionResult(executions, recoveredFailureStepIds, replanAttempts);
        }

        List<AgentPlanStep> remainingSteps = new ArrayList<>(plan.steps());
        while (!remainingSteps.isEmpty()) {
            AgentPlanStep step = remainingSteps.remove(0);
            PlanStepExecution execution = executeStep(step, successStepIds, failedFingerprints);
            executions.add(execution);
            if (execution.status() == PlanStepStatus.SUCCESS) {
                successStepIds.add(execution.stepId());
                continue;
            }
            if (!shouldReplan(execution) || replanAttempts >= MAX_REPLAN_ATTEMPTS) {
                continue;
            }

            failedFingerprints.add(fingerprint(step));
            PlanExecutionState state = new PlanExecutionState(
                    plan.objective(),
                    executions,
                    remainingSteps,
                    List.copyOf(failedFingerprints)
            );
            AgentPlan replannedPlan = agentReplanner.replan(conversationContext, userMessage, state);
            if (AgentReplanner.FALLBACK_RATIONALE.equals(replannedPlan.rationale())) {
                replanAttempts++;
                continue;
            }
            AgentPlan reindexedPlan = AgentPlanNormalizer.reindexRemainingSteps(
                    replannedPlan,
                    nextStepId(plan, executions, remainingSteps),
                    successStepIds
            );
            remainingSteps = new ArrayList<>(reindexedPlan.steps());
            if (!remainingSteps.isEmpty()) {
                recoveredFailureStepIds.add(execution.stepId());
            }
            replanAttempts++;
        }
        return new PlanExecutionResult(executions, recoveredFailureStepIds, replanAttempts);
    }

    private PlanStepExecution executeStep(AgentPlanStep step, Set<Integer> successStepIds, Set<String> failedFingerprints) {
        if (!successStepIds.containsAll(step.dependsOn())) {
            // 依赖失败时不继续执行，避免后续工具基于缺失前置事实产生误导性结果。
            return toExecution(step, PlanStepStatus.SKIPPED, null, "前置步骤未成功，已跳过本步。");
        }
        if (TextUtil.isBlank(step.action())) {
            return toExecution(step, PlanStepStatus.FAILED, null, "计划步骤缺少 action。");
        }
        if (failedFingerprints.contains(fingerprint(step))) {
            // Replanner 不应重复已经失败的同一工具方案，直接拦截能防止重规划在失败路径上来回振荡。
            return toExecution(step, PlanStepStatus.FAILED, null, "Replanner 重复了已失败的工具方案：" + fingerprint(step));
        }
        if (toolRegistry.getTool(step.action()) == null) {
            return toExecution(step, PlanStepStatus.FAILED, null, "计划引用了不存在的工具：" + step.action());
        }
        Observation observation = toolExecutor.execute(new ToolCall(step.action(), TextUtil.trimToDefault(step.actionInput(), "")));
        String result = observation == null ? null : observation.result();
        if (isToolError(result)) {
            return toExecution(step, PlanStepStatus.FAILED, result, result);
        }
        if (TextUtil.isBlank(result) && TextUtil.hasText(step.expectedObservation())) {
            return toExecution(step, PlanStepStatus.FAILED, result, "工具返回为空，未满足预期观察：" + step.expectedObservation());
        }
        return toExecution(step, PlanStepStatus.SUCCESS, result, null);
    }

    private boolean shouldReplan(PlanStepExecution execution) {
        return execution.status() == PlanStepStatus.FAILED;
    }

    private String fingerprint(AgentPlanStep step) {
        return TextUtil.trimToDefault(step.action(), "") + "::" + TextUtil.trimToDefault(step.actionInput(), "");
    }

    private int nextStepId(AgentPlan plan, List<PlanStepExecution> executions, List<AgentPlanStep> remainingSteps) {
        int maxId = 0;
        if (plan != null) {
            for (AgentPlanStep step : plan.steps()) {
                maxId = Math.max(maxId, step.id());
            }
        }
        for (PlanStepExecution execution : executions) {
            maxId = Math.max(maxId, execution.stepId());
        }
        for (AgentPlanStep step : remainingSteps) {
            maxId = Math.max(maxId, step.id());
        }
        return maxId + 1;
    }

    private PlanStepExecution toExecution(AgentPlanStep step, PlanStepStatus status, String observation, String errorMessage) {
        return new PlanStepExecution(
                step.id(),
                step.description(),
                step.action(),
                step.actionInput(),
                step.dependsOn(),
                step.expectedObservation(),
                status,
                observation,
                errorMessage
        );
    }

    private boolean isToolError(String result) {
        return TextUtil.trimToDefault(result, "").startsWith("Error:");
    }

    private List<AgentStep> toAgentSteps(List<PlanStepExecution> executions) {
        List<AgentStep> steps = new ArrayList<>();
        int index = 1;
        for (PlanStepExecution execution : executions) {
            steps.add(new AgentStep(
                    index++,
                    "计划执行：" + execution.description(),
                    execution.action(),
                    execution.actionInput(),
                    TextUtil.trimToDefault(execution.observation(), execution.errorMessage())
            ));
        }
        return steps;
    }

    private String resolveStopReason(AgentPlan plan, PlanExecutionResult executionResult) {
        if (plan == null || plan.steps().isEmpty()) {
            return "Planner 未生成可执行步骤，已直接进入合成兜底。";
        }
        Set<Integer> recoveredIds = new HashSet<>(executionResult.recoveredFailureStepIds());
        long failedCount = executionResult.executions().stream()
                .filter(execution -> execution.status() == PlanStepStatus.FAILED)
                .filter(execution -> !recoveredIds.contains(execution.stepId()))
                .count();
        long skippedCount = executionResult.executions().stream()
                .filter(execution -> execution.status() == PlanStepStatus.SKIPPED)
                .count();
        if (failedCount == 0 && skippedCount == 0) {
            return null;
        }
        return "计划执行未完全成功：失败 " + failedCount + " 步，跳过 " + skippedCount + " 步。";
    }

    private record PlanExecutionResult(
            List<PlanStepExecution> executions,
            List<Integer> recoveredFailureStepIds,
            int replanAttempts
    ) {

        private PlanExecutionResult {
            executions = executions == null ? List.of() : List.copyOf(executions);
            recoveredFailureStepIds = recoveredFailureStepIds == null ? List.of() : List.copyOf(recoveredFailureStepIds);
        }
    }
}
