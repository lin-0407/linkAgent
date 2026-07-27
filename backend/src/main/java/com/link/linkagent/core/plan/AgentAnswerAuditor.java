package com.link.linkagent.core.plan;

import com.link.linkagent.core.citation.AgentEvidence;
import com.link.linkagent.core.citation.AnswerAuditReport;
import com.link.linkagent.core.citation.CitedAnswer;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 最终答案审查器。
 * <p>
 * 审查器独立于 Synthesizer，是为了让“写答案”和“挑问题”分离，减少同一个角色自证正确的风险。
 */
@Component
public class AgentAnswerAuditor {

    private static final Logger log = LoggerFactory.getLogger(AgentAnswerAuditor.class);

    private final LLMService llmService;
    private final PromptService promptService;

    public AgentAnswerAuditor(LLMService llmService, PromptService promptService) {
        this.llmService = llmService;
        this.promptService = promptService;
    }

    public AnswerAuditReport audit(String userMessage, CitedAnswer answer, List<AgentEvidence> evidences) {
        try {
            AnswerAuditReport report = llmService.chatStructured(
                    promptService.get("agent_answer_auditor.system"),
                    buildAuditUserMessage(userMessage, answer, evidences),
                    AnswerAuditReport.class
            );
            return report == null
                    ? new AnswerAuditReport(true, "审查器返回空对象，已保留原答案。", List.of(), List.of())
                    : report;
        } catch (RuntimeException exception) {
            // 审查器本身失败时不应让主回答直接失败；记录日志后保留原答案，避免把辅助质检变成新的可用性单点。
            log.warn("Agent 答案审查失败，保留 Synthesizer 原答案。error={}", exception.getMessage());
            return new AnswerAuditReport(true, "审查器执行失败，已保留原答案。", List.of(), List.of());
        }
    }

    private String buildAuditUserMessage(String userMessage, CitedAnswer answer, List<AgentEvidence> evidences) {
        return """
                请审查这份带引用的 Agent 最终回答。

                【用户当前请求】
                %s

                【候选回答】
                %s

                【可用证据】
                %s

                请检查：
                1. 是否曲解、缩窄或擅自扩展了用户问题。
                2. 是否回答完用户真正要求的内容。
                3. 是否存在自相矛盾。
                4. 是否存在没有证据 id 的事实性断言。
                5. 是否把 Worker 推理当成外部事实。
                """.formatted(
                TextUtil.trimToDefault(userMessage, "（用户请求为空）"),
                answer == null ? "（候选回答为空）" : answer.toString(),
                formatEvidences(evidences)
        );
    }

    private String formatEvidences(List<AgentEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return "没有可用证据。";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentEvidence evidence : evidences) {
            builder.append("[")
                    .append(evidence.evidenceId())
                    .append("] ")
                    .append(evidence.sourceType())
                    .append("｜")
                    .append(evidence.sourceRef())
                    .append("｜")
                    .append(evidence.quote())
                    .append("\n");
        }
        return builder.toString();
    }
}
