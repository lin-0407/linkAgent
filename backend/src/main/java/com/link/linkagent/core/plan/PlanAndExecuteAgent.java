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

    private final AgentPlanner agentPlanner;
    private final ToolExecutor toolExecutor;
    private final ToolRegistry toolRegistry;
    private final AgentAnswerSynthesizer answerSynthesizer;

    public PlanAndExecuteAgent(AgentPlanner agentPlanner,
                               ToolExecutor toolExecutor,
                               ToolRegistry toolRegistry,
                               AgentAnswerSynthesizer answerSynthesizer) {
        this.agentPlanner = agentPlanner;
        this.toolExecutor = toolExecutor;
        this.toolRegistry = toolRegistry;
        this.answerSynthesizer = answerSynthesizer;
    }

    public AgentRunResult run(String conversationContext, String userMessage) {
        AgentPlan plan = agentPlanner.plan(conversationContext, userMessage);
        List<PlanStepExecution> executions = executePlan(plan);
        List<AgentStep> steps = toAgentSteps(executions);
        AgentPlanTrace trace = AgentPlanTrace.from(plan, executions);
        String stopReason = resolveStopReason(plan, executions);
        String finalAnswer = answerSynthesizer.synthesizePlanResult(conversationContext, userMessage, plan, executions);
        return AgentRunResult.planExecute(finalAnswer, stopReason, steps, trace);
    }

    private List<PlanStepExecution> executePlan(AgentPlan plan) {
        List<PlanStepExecution> executions = new ArrayList<>();
        Set<Integer> successStepIds = new HashSet<>();
        if (plan == null || plan.steps().isEmpty()) {
            return executions;
        }
        for (AgentPlanStep step : plan.steps()) {
            PlanStepExecution execution = executeStep(step, successStepIds);
            executions.add(execution);
            if (execution.status() == PlanStepStatus.SUCCESS) {
                successStepIds.add(execution.stepId());
            }
        }
        return executions;
    }

    private PlanStepExecution executeStep(AgentPlanStep step, Set<Integer> successStepIds) {
        if (!successStepIds.containsAll(step.dependsOn())) {
            // 依赖失败时不继续执行，避免后续工具基于缺失前置事实产生误导性结果。
            return toExecution(step, PlanStepStatus.SKIPPED, null, "前置步骤未成功，已跳过本步。");
        }
        if (TextUtil.isBlank(step.action())) {
            return toExecution(step, PlanStepStatus.FAILED, null, "计划步骤缺少 action。");
        }
        if (toolRegistry.getTool(step.action()) == null) {
            return toExecution(step, PlanStepStatus.FAILED, null, "计划引用了不存在的工具：" + step.action());
        }
        Observation observation = toolExecutor.execute(new ToolCall(step.action(), TextUtil.trimToDefault(step.actionInput(), "")));
        if (isToolError(observation.result())) {
            return toExecution(step, PlanStepStatus.FAILED, observation.result(), observation.result());
        }
        return toExecution(step, PlanStepStatus.SUCCESS, observation.result(), null);
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

    private String resolveStopReason(AgentPlan plan, List<PlanStepExecution> executions) {
        if (plan == null || plan.steps().isEmpty()) {
            return "Planner 未生成可执行步骤，已直接进入合成兜底。";
        }
        long failedCount = executions.stream()
                .filter(execution -> execution.status() == PlanStepStatus.FAILED)
                .count();
        long skippedCount = executions.stream()
                .filter(execution -> execution.status() == PlanStepStatus.SKIPPED)
                .count();
        if (failedCount == 0 && skippedCount == 0) {
            return null;
        }
        return "计划执行未完全成功：失败 " + failedCount + " 步，跳过 " + skippedCount + " 步。";
    }
}
