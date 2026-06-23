package com.link.linkagent.core.plan;

import com.link.linkagent.core.AgentToolPromptFormatter;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.tool.Tool;
import com.link.linkagent.tool.ToolRegistry;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.Collection;
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
        return AgentPlanNormalizer.normalize(plan, "Planner 返回空对象");
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

}
