package com.link.linkagent.core.plan;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 答案合成器。
 * <p>
 * Planner/Worker 只产生中间事实，最终回答统一交给 Synthesizer，避免每条执行路径自己拼一段不一致的自然语言。
 */
@Component
public class AgentAnswerSynthesizer {

    private final LLMService llmService;
    private final PromptService promptService;

    public AgentAnswerSynthesizer(LLMService llmService, PromptService promptService) {
        this.llmService = llmService;
        this.promptService = promptService;
    }

    public String synthesizePlanResult(String conversationContext, String userMessage,
                                       AgentPlan plan, List<PlanStepExecution> executions) {
        String answer = llmService.chat(
                promptService.get("agent_plan_execute_synthesizer.system"),
                buildPlanSynthesisUserMessage(conversationContext, userMessage, plan, executions)
        );
        return TextUtil.trimToDefault(answer, "计划已执行，但合成器没有返回有效回答。");
    }

    public String synthesizeMultiAgentResult(String conversationContext, String userMessage, String workerSummary) {
        String answer = llmService.chat(
                promptService.get("agent_multi_synthesizer.system"),
                """
                        请基于多个 Worker 的执行结果回答用户。

                        【用户当前请求】
                        %s

                        【可参考上下文】
                        %s

                        【Worker 执行结果】
                        %s
                        """.formatted(
                        TextUtil.trimToDefault(userMessage, "（用户请求为空）"),
                        TextUtil.trimToDefault(conversationContext, "（无上下文）"),
                        TextUtil.trimToDefault(workerSummary, "（无 Worker 结果）")
                )
        );
        return TextUtil.trimToDefault(answer, "多 Agent 已执行，但合成器没有返回有效回答。");
    }

    private String buildPlanSynthesisUserMessage(String conversationContext, String userMessage,
                                                 AgentPlan plan, List<PlanStepExecution> executions) {
        return """
                请把计划执行结果合成为给用户的最终回答。

                【用户当前请求】
                %s

                【可参考上下文】
                %s

                【计划】
                %s

                【执行结果】
                %s
                """.formatted(
                TextUtil.trimToDefault(userMessage, "（用户请求为空）"),
                TextUtil.trimToDefault(conversationContext, "（无上下文）"),
                formatPlan(plan),
                formatExecutions(executions)
        );
    }

    private String formatPlan(AgentPlan plan) {
        if (plan == null) {
            return "未生成计划。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("目标：").append(plan.objective()).append("\n")
                .append("规划依据：").append(plan.rationale()).append("\n")
                .append("覆盖检查：").append(plan.coverageCheck()).append("\n");
        for (AgentPlanStep step : plan.steps()) {
            builder.append(step.id()).append(". ")
                    .append(step.description())
                    .append(" -> ").append(step.action())
                    .append("(").append(step.actionInput()).append(")")
                    .append("\n");
        }
        return builder.toString();
    }

    public String formatExecutions(List<PlanStepExecution> executions) {
        if (executions == null || executions.isEmpty()) {
            return "没有执行步骤。";
        }
        StringBuilder builder = new StringBuilder();
        for (PlanStepExecution execution : executions) {
            builder.append(execution.stepId()).append(". ")
                    .append(execution.status())
                    .append("｜").append(execution.description())
                    .append("｜工具：").append(TextUtil.trimToDefault(execution.action(), "无"))
                    .append("｜观察：").append(TextUtil.preview(execution.observation(), 900, "无"))
                    .append("｜错误：").append(TextUtil.trimToDefault(execution.errorMessage(), "无"))
                    .append("\n");
        }
        return builder.toString();
    }
}
