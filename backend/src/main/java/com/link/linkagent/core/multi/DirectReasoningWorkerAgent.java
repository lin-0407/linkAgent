package com.link.linkagent.core.multi;

import com.link.linkagent.core.citation.AgentEvidence;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 多 Agent 模式下的直接推理 Worker。
 * <p>
 * 这个 Worker 不调用工具，负责解释、归纳、改写和创作建议等轻量推理任务。
 * 它独立成 Bean，是为了让 Orchestrator 可以在“要工具取证”和“只需语言推理”之间做真实路由。
 */
@Component
public class DirectReasoningWorkerAgent implements WorkerAgent {

    private final LLMService llmService;
    private final PromptService promptService;

    public DirectReasoningWorkerAgent(LLMService llmService, PromptService promptService) {
        this.llmService = llmService;
        this.promptService = promptService;
    }

    @Override
    public String name() {
        return "direct_reasoning_worker";
    }

    @Override
    public String role() {
        return "直接推理 Agent";
    }

    @Override
    public String capability() {
        return "适合不需要工具调用的归纳、解释、结构化表达、文案改写和创作建议。";
    }

    @Override
    public AgentWorkerTrace execute(WorkerCall call, String conversationContext, String userMessage) {
        try {
            String summary = llmService.chat(
                    promptService.get("agent_multi_direct_worker.system"),
                    buildWorkerUserMessage(call, conversationContext, userMessage)
            );
            List<AgentEvidence> evidences = List.of(AgentEvidence.fromWorkerSummary(call.id(), name(), summary));
            return new AgentWorkerTrace(
                    call.id(),
                    name(),
                    role(),
                    capability(),
                    WorkerStatus.SUCCESS,
                    call.subTask(),
                    call.sharedContext(),
                    TextUtil.trimToDefault(summary, "Direct Worker 没有返回有效结果。"),
                    WorkerBrief.fromSummary(summary, evidenceIds(evidences), WorkerStatus.SUCCESS),
                    evidences,
                    null,
                    null,
                    List.of()
            );
        } catch (RuntimeException exception) {
            return new AgentWorkerTrace(
                    call.id(),
                    name(),
                    role(),
                    capability(),
                    WorkerStatus.FAILED,
                    call.subTask(),
                    call.sharedContext(),
                    null,
                    WorkerBrief.fromSummary(exception.getMessage(), List.of(), WorkerStatus.FAILED),
                    List.of(),
                    TextUtil.trimToDefault(exception.getMessage(), "Direct Worker 执行异常"),
                    null,
                    List.of()
            );
        }
    }

    private String buildWorkerUserMessage(WorkerCall call, String conversationContext, String userMessage) {
        return """
                请完成 Orchestrator 分配的子任务。

                【用户原始请求】
                %s

                【子任务】
                %s

                【共享上下文】
                %s

                【对话上下文】
                %s
                """.formatted(
                TextUtil.trimToDefault(userMessage, "（用户请求为空）"),
                TextUtil.trimToDefault(call.subTask(), "（无子任务）"),
                TextUtil.trimToDefault(call.sharedContext(), "（无共享上下文）"),
                TextUtil.trimToDefault(conversationContext, "（无上下文）")
        );
    }

    private List<String> evidenceIds(List<AgentEvidence> evidences) {
        return evidences.stream()
                .map(AgentEvidence::evidenceId)
                .toList();
    }
}
