package com.link.linkagent.core.plan;

import com.link.linkagent.core.AgentToolPromptFormatter;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.tool.Tool;
import com.link.linkagent.tool.ToolRegistry;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Plan-and-Execute 的重规划 Agent。
 * <p>
 * Replanner 只在执行失败或结果不可用时介入，目的是修正剩余路线，而不是推翻已经成功的事实。
 */
@Component
public class AgentReplanner {

    static final String FALLBACK_RATIONALE = "Replanner 执行失败，保留原剩余计划。";

    private static final Logger log = LoggerFactory.getLogger(AgentReplanner.class);

    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final PromptService promptService;

    public AgentReplanner(LLMService llmService, ToolRegistry toolRegistry, PromptService promptService) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.promptService = promptService;
    }

    public AgentPlan replan(String conversationContext, String userMessage, PlanExecutionState state) {
        try {
            Collection<Tool> tools = toolRegistry.getAllTools();
            String systemPrompt = promptService.render("agent_plan_execute_replanner.system",
                    Map.of("toolList", AgentToolPromptFormatter.format(tools)));
            AgentPlan plan = llmService.chatStructured(
                    systemPrompt,
                    buildReplanUserMessage(conversationContext, userMessage, state),
                    AgentPlan.class
            );
            return AgentPlanNormalizer.normalize(plan, "Replanner 返回空对象");
        } catch (RuntimeException exception) {
            // Replanner 是韧性增强，不应该因为提示词缺失或结构化解析失败让 PaE 主流程直接中断。
            log.warn("PaE 重规划失败，保留原剩余计划。error={}", exception.getMessage());
            return new AgentPlan(
                    TextUtil.trimToDefault(state.objective(), "沿用原计划"),
                    state.remainingSteps(),
                    FALLBACK_RATIONALE,
                    "未能重新评估剩余诉求"
            );
        }
    }

    private String buildReplanUserMessage(String conversationContext, String userMessage, PlanExecutionState state) {
        return """
                请基于当前执行现场重新规划剩余步骤。

                【用户当前请求】
                %s

                【可参考上下文】
                %s

                【原目标】
                %s

                【已执行步骤】
                %s

                【尚未执行的原步骤】
                %s

                【已失败方案指纹】
                %s

                要求：
                1. 只输出剩余步骤，不要重复已经成功的步骤。
                2. 不要再次使用已失败方案指纹里的 action + actionInput。
                3. action 必须来自工具清单。
                4. 如果剩余诉求无法继续满足，返回空 steps，并在 coverageCheck 说明原因。
                """.formatted(
                TextUtil.trimToDefault(userMessage, "（用户请求为空）"),
                TextUtil.preview(conversationContext, 1200, "（无上下文）"),
                TextUtil.trimToDefault(state.objective(), "未说明目标"),
                formatExecutions(state.executedSteps()),
                formatRemainingSteps(state.remainingSteps()),
                state.failedFingerprints()
        );
    }

    private String formatExecutions(List<PlanStepExecution> executions) {
        if (executions == null || executions.isEmpty()) {
            return "没有已执行步骤。";
        }
        StringBuilder builder = new StringBuilder();
        for (PlanStepExecution execution : executions) {
            builder.append(execution.stepId()).append(". ")
                    .append(execution.status())
                    .append("｜").append(execution.description())
                    .append("｜工具：").append(TextUtil.trimToDefault(execution.action(), "无"))
                    .append("｜输入：").append(TextUtil.preview(execution.actionInput(), 260, "无"))
                    .append("｜观察：").append(TextUtil.preview(execution.observation(), 500, "无"))
                    .append("｜错误：").append(TextUtil.trimToDefault(execution.errorMessage(), "无"))
                    .append("\n");
        }
        return builder.toString();
    }

    private String formatRemainingSteps(List<AgentPlanStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "没有剩余步骤。";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentPlanStep step : steps) {
            builder.append(step.id()).append(". ")
                    .append(step.description())
                    .append(" -> ").append(step.action())
                    .append("(").append(step.actionInput()).append(")")
                    .append("｜依赖：").append(step.dependsOn())
                    .append("\n");
        }
        return builder.toString();
    }
}
