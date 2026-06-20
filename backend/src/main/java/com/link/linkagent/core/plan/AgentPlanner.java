package com.link.linkagent.core.plan;

import com.link.linkagent.core.AgentToolPromptFormatter;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.tool.Tool;
import com.link.linkagent.tool.ToolRegistry;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Plan-and-Execute 的 Planner Agent。
 * <p>
 * 这个 Bean 只负责把用户目标和工具清单变成结构化计划，不执行工具也不合成答案。
 * 把规划独立出来，是为了让 PaE 和多 Agent Worker 能复用同一份计划契约。
 */
@Component
public class AgentPlanner {

    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final PromptService promptService;

    public AgentPlanner(LLMService llmService, ToolRegistry toolRegistry, PromptService promptService) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.promptService = promptService;
    }

    public AgentPlan plan(String conversationContext, String userMessage) {
        Collection<Tool> tools = toolRegistry.getAllTools();
        String systemPrompt = promptService.render("agent_plan_execute_planner.system",
                Map.of("toolList", AgentToolPromptFormatter.format(tools)));
        AgentPlan plan = llmService.chatStructured(
                systemPrompt,
                buildPlannerUserMessage(conversationContext, userMessage),
                AgentPlan.class
        );
        return normalizePlan(plan);
    }

    private String buildPlannerUserMessage(String conversationContext, String userMessage) {
        return """
                请为下面的用户请求生成 Plan-and-Execute 计划。

                【用户当前请求】
                %s

                【可参考上下文】
                %s
                """.formatted(
                TextUtil.trimToDefault(userMessage, "（用户请求为空）"),
                TextUtil.trimToDefault(conversationContext, "（无上下文）")
        );
    }

    private AgentPlan normalizePlan(AgentPlan rawPlan) {
        if (rawPlan == null) {
            return new AgentPlan("未生成计划", List.of(), "Planner 返回空对象", "未覆盖用户诉求");
        }
        List<AgentPlanStep> normalizedSteps = rawPlan.steps().stream()
                .filter(step -> step != null)
                // Planner 生成乱序时，执行器仍按 id 稳定执行，便于前端回放和问题复现。
                .sorted(Comparator.comparingInt(AgentPlanStep::id))
                .map(this::normalizeStep)
                .toList();
        return new AgentPlan(
                TextUtil.trimToDefault(rawPlan.objective(), "未说明目标"),
                normalizedSteps,
                TextUtil.trimToDefault(rawPlan.rationale(), "未说明规划依据"),
                TextUtil.trimToDefault(rawPlan.coverageCheck(), "未说明覆盖检查")
        );
    }

    private AgentPlanStep normalizeStep(AgentPlanStep step) {
        return new AgentPlanStep(
                step.id(),
                TextUtil.trimToDefault(step.description(), "未说明步骤目标"),
                TextUtil.trimToDefault(step.action(), ""),
                TextUtil.trimToDefault(step.actionInput(), ""),
                step.dependsOn(),
                TextUtil.trimToDefault(step.expectedObservation(), "")
        );
    }
}
