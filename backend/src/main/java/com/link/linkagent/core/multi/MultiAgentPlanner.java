package com.link.linkagent.core.multi;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 多 Agent Orchestrator 的 Planner。
 * <p>
 * 它只决定调用哪些 Worker，不直接处理业务细节。Worker 能力来自 Spring Bean 自动注入的清单。
 */
@Component
public class MultiAgentPlanner {

    private final LLMService llmService;
    private final PromptService promptService;

    public MultiAgentPlanner(LLMService llmService, PromptService promptService) {
        this.llmService = llmService;
        this.promptService = promptService;
    }

    public WorkerPlan plan(String conversationContext, String userMessage, List<WorkerAgent> workers) {
        String systemPrompt = promptService.render("agent_multi_planner.system",
                Map.of("workerList", formatWorkers(workers)));
        WorkerPlan plan = llmService.chatStructured(
                systemPrompt,
                buildPlannerUserMessage(conversationContext, userMessage),
                WorkerPlan.class
        );
        return normalizePlan(plan);
    }

    private String buildPlannerUserMessage(String conversationContext, String userMessage) {
        return """
                请为下面的用户请求生成多 Agent Worker 调度计划。

                【用户当前请求】
                %s

                【可参考上下文】
                %s
                """.formatted(
                TextUtil.trimToDefault(userMessage, "（用户请求为空）"),
                TextUtil.trimToDefault(conversationContext, "（无上下文）")
        );
    }

    private String formatWorkers(List<WorkerAgent> workers) {
        StringBuilder builder = new StringBuilder();
        for (WorkerAgent worker : workers) {
            builder.append("- ")
                    .append(worker.name())
                    .append("｜")
                    .append(worker.role())
                    .append("：")
                    .append(worker.capability())
                    .append("\n");
        }
        return builder.toString();
    }

    private WorkerPlan normalizePlan(WorkerPlan rawPlan) {
        if (rawPlan == null) {
            return new WorkerPlan("未生成 Worker 计划", List.of(), "Planner 返回空对象", "未覆盖用户诉求");
        }
        List<WorkerCall> calls = rawPlan.calls().stream()
                .filter(call -> call != null)
                .sorted(Comparator.comparingInt(WorkerCall::id))
                .map(this::normalizeCall)
                .toList();
        return new WorkerPlan(
                TextUtil.trimToDefault(rawPlan.objective(), "未说明目标"),
                calls,
                TextUtil.trimToDefault(rawPlan.rationale(), "未说明调度依据"),
                TextUtil.trimToDefault(rawPlan.coverageCheck(), "未说明覆盖检查")
        );
    }

    private WorkerCall normalizeCall(WorkerCall call) {
        return new WorkerCall(
                call.id(),
                TextUtil.trimToDefault(call.workerName(), ""),
                TextUtil.trimToDefault(call.subTask(), "未说明子任务"),
                TextUtil.trimToDefault(call.sharedContext(), ""),
                call.dependsOn()
        );
    }
}
