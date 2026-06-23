package com.link.linkagent.core.plan;

import com.link.linkagent.core.citation.AgentEvidence;
import com.link.linkagent.core.citation.AnswerAuditReport;
import com.link.linkagent.core.citation.CitedAnswer;
import com.link.linkagent.core.citation.CitedStatement;
import com.link.linkagent.core.citation.EvidenceSourceType;
import com.link.linkagent.core.multi.AgentWorkerTrace;
import com.link.linkagent.core.multi.WorkerBrief;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 答案合成器。
 * <p>
 * Planner/Worker 只产生中间事实，最终回答统一交给 Synthesizer，避免每条执行路径自己拼一段不一致的自然语言。
 */
@Component
public class AgentAnswerSynthesizer {

    private static final int MAX_AUDIT_REWRITE_ROUNDS = 2;

    private final LLMService llmService;
    private final PromptService promptService;
    private final AgentAnswerAuditor answerAuditor;

    public AgentAnswerSynthesizer(LLMService llmService, PromptService promptService,
                                  AgentAnswerAuditor answerAuditor) {
        this.llmService = llmService;
        this.promptService = promptService;
        this.answerAuditor = answerAuditor;
    }

    public String synthesizePlanResult(String conversationContext, String userMessage,
                                       AgentPlan plan, List<PlanStepExecution> executions) {
        List<AgentEvidence> evidences = buildPlanEvidences(executions);
        return synthesizeWithEvidence(
                "agent_plan_execute_synthesizer.system",
                userMessage,
                buildPlanSynthesisUserMessage(conversationContext, userMessage, plan, executions),
                evidences
        );
    }

    public String synthesizeMultiAgentResult(String conversationContext, String userMessage,
                                             List<AgentWorkerTrace> workerTraces) {
        List<AgentEvidence> evidences = buildWorkerEvidences(workerTraces);
        return synthesizeWithEvidence(
                "agent_multi_synthesizer.system",
                userMessage,
                buildMultiAgentSynthesisUserMessage(conversationContext, userMessage, workerTraces),
                evidences
        );
    }

    public String synthesizeMultiAgentResult(String conversationContext, String userMessage, String workerSummary) {
        AgentEvidence evidence = new AgentEvidence(
                "W-SUMMARY-E1",
                EvidenceSourceType.WORKER_REASONING,
                "legacy.worker.summary",
                workerSummary,
                workerSummary,
                0.5D
        );
        return synthesizeWithEvidence(
                "agent_multi_synthesizer.system",
                userMessage,
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
                        TextUtil.preview(conversationContext, 1200, "（无上下文）"),
                        TextUtil.trimToDefault(workerSummary, "（无 Worker 结果）")
                ),
                List.of(evidence)
        );
    }

    private String synthesizeWithEvidence(String systemPromptKey, String originalUserMessage,
                                          String synthesisUserMessage, List<AgentEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return renderAnswer(CitedAnswer.empty("没有可引用证据，无法可靠回答。"), null);
        }
        CitedAnswer answer = normalizeAnswerEvidenceIds(requestCitedAnswer(systemPromptKey, synthesisUserMessage, evidences, null), evidences);
        AnswerAuditReport auditReport = answerAuditor.audit(originalUserMessage, answer, evidences);
        int rewriteRound = 0;
        while (!auditReport.passed() && rewriteRound < MAX_AUDIT_REWRITE_ROUNDS) {
            answer = normalizeAnswerEvidenceIds(requestCitedAnswer(systemPromptKey, synthesisUserMessage, evidences, auditReport), evidences);
            auditReport = answerAuditor.audit(originalUserMessage, answer, evidences);
            rewriteRound++;
        }
        return renderAnswer(answer, auditReport);
    }

    private CitedAnswer requestCitedAnswer(String systemPromptKey, String synthesisUserMessage,
                                           List<AgentEvidence> evidences, AnswerAuditReport previousAudit) {
        CitedAnswer answer = llmService.chatStructured(
                promptService.get(systemPromptKey),
                """
                        %s

                        【可用证据】
                        %s

                        【输出要求】
                        必须输出 JSON 对象，字段匹配 CitedAnswer：statements、limitations。
                        statements 中每条为 text 和 evidenceIds。
                        每个事实性 statement 必须引用 evidenceIds；没有证据时不要输出该事实。
                        可以基于证据给建议，但要避免把 Worker 推理当成外部事实。
                        若证据不足，请在 limitations 中说明。

                        【上一轮审查反馈】
                        %s
                        """.formatted(
                        synthesisUserMessage,
                        formatEvidences(evidences),
                        formatAuditFeedback(previousAudit)
                ),
                CitedAnswer.class
        );
        return answer == null ? CitedAnswer.empty("合成器返回空对象，无法可靠回答。") : answer;
    }

    private CitedAnswer normalizeAnswerEvidenceIds(CitedAnswer answer, List<AgentEvidence> evidences) {
        if (answer == null) {
            return CitedAnswer.empty("合成器返回空对象，无法可靠回答。");
        }
        Set<String> allowedEvidenceIds = evidences.stream()
                .map(AgentEvidence::evidenceId)
                .collect(java.util.stream.Collectors.toSet());
        List<String> droppedStatements = new java.util.ArrayList<>();
        List<CitedStatement> normalizedStatements = answer.statements().stream()
                .map(statement -> normalizeStatement(statement, allowedEvidenceIds, droppedStatements))
                .filter(statement -> statement != null)
                .toList();
        List<String> limitations = new java.util.ArrayList<>(answer.limitations());
        limitations.addAll(droppedStatements);
        return new CitedAnswer(normalizedStatements, limitations);
    }

    private CitedStatement normalizeStatement(CitedStatement statement, Set<String> allowedEvidenceIds,
                                              List<String> droppedStatements) {
        List<String> validEvidenceIds = statement.evidenceIds().stream()
                .filter(allowedEvidenceIds::contains)
                .distinct()
                .toList();
        if (validEvidenceIds.isEmpty()) {
            // 证据 ID 必须由代码层过滤，避免模型编造一个看似可信、实际不存在的引用。
            droppedStatements.add("已移除缺少有效证据的陈述：" + TextUtil.preview(statement.text(), 120, statement.text()));
            return null;
        }
        return new CitedStatement(statement.text(), validEvidenceIds);
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
                TextUtil.preview(conversationContext, 1200, "（无上下文）"),
                formatPlan(plan),
                formatExecutions(executions)
        );
    }

    private String buildMultiAgentSynthesisUserMessage(String conversationContext, String userMessage,
                                                       List<AgentWorkerTrace> workerTraces) {
        return """
                请基于多个 Worker 的结构化摘要和证据回答用户。

                【用户当前请求】
                %s

                【可参考上下文】
                %s

                【Worker 摘要层】
                %s
                """.formatted(
                TextUtil.trimToDefault(userMessage, "（用户请求为空）"),
                TextUtil.preview(conversationContext, 1200, "（无上下文）"),
                formatWorkerBriefs(workerTraces)
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

    private List<AgentEvidence> buildPlanEvidences(List<PlanStepExecution> executions) {
        if (executions == null || executions.isEmpty()) {
            return List.of();
        }
        return executions.stream()
                .map(this::toEvidence)
                .filter(evidence -> evidence != null)
                .toList();
    }

    private AgentEvidence toEvidence(PlanStepExecution execution) {
        if (execution.status() == PlanStepStatus.SUCCESS && TextUtil.hasText(execution.observation())) {
            return AgentEvidence.fromPlanStep(execution.stepId(), execution.action(), execution.observation());
        }
        if (TextUtil.hasText(execution.errorMessage())) {
            return new AgentEvidence(
                    "P" + execution.stepId() + "-ERR",
                    EvidenceSourceType.SYSTEM_LIMITATION,
                    "plan.step." + execution.stepId() + ":" + TextUtil.trimToDefault(execution.action(), "unknown_tool"),
                    execution.errorMessage(),
                    execution.errorMessage(),
                    0.4D
            );
        }
        return null;
    }

    private List<AgentEvidence> buildWorkerEvidences(List<AgentWorkerTrace> workerTraces) {
        if (workerTraces == null || workerTraces.isEmpty()) {
            return List.of();
        }
        Map<String, AgentEvidence> evidenceMap = new LinkedHashMap<>();
        for (AgentWorkerTrace trace : workerTraces) {
            for (AgentEvidence evidence : trace.evidences()) {
                evidenceMap.putIfAbsent(evidence.evidenceId(), evidence);
            }
            if (TextUtil.hasText(trace.errorMessage())) {
                AgentEvidence errorEvidence = new AgentEvidence(
                        "W" + trace.callId() + "-ERR",
                        EvidenceSourceType.SYSTEM_LIMITATION,
                        "worker." + trace.callId() + ":" + trace.workerName(),
                        trace.errorMessage(),
                        trace.errorMessage(),
                        0.35D
                );
                evidenceMap.putIfAbsent(errorEvidence.evidenceId(), errorEvidence);
            }
        }
        return List.copyOf(evidenceMap.values());
    }

    private String formatWorkerBriefs(List<AgentWorkerTrace> workerTraces) {
        if (workerTraces == null || workerTraces.isEmpty()) {
            return "没有 Worker 摘要。";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentWorkerTrace trace : workerTraces) {
            WorkerBrief brief = trace.brief();
            builder.append(trace.callId()).append(". ")
                    .append(trace.workerName())
                    .append("｜").append(trace.status())
                    .append("｜子任务：").append(trace.subTask())
                    .append("｜结论：").append(brief == null ? TextUtil.preview(trace.summary(), 600, "无")
                            : brief.coreConclusion())
                    .append("｜证据ID：").append(brief == null ? List.of() : brief.evidenceIds())
                    .append("｜未解决：").append(brief == null ? List.of() : brief.unresolvedQuestions())
                    .append("\n");
        }
        return builder.toString();
    }

    private String formatEvidences(List<AgentEvidence> evidences) {
        if (evidences == null || evidences.isEmpty()) {
            return "没有可用证据。";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentEvidence evidence : evidences) {
            builder.append("[")
                    .append(evidence.evidenceId())
                    .append("] 类型=").append(evidence.sourceType())
                    .append("｜来源=").append(evidence.sourceRef())
                    .append("｜置信度=").append(evidence.confidence())
                    .append("｜摘录=").append(evidence.quote())
                    .append("\n");
        }
        return builder.toString();
    }

    private String formatAuditFeedback(AnswerAuditReport auditReport) {
        if (auditReport == null || auditReport.passed()) {
            return "无。";
        }
        return """
                审查未通过：%s
                修改要求：%s
                问题：%s
                """.formatted(
                TextUtil.trimToDefault(auditReport.overallComment(), "未说明"),
                auditReport.rewriteInstructions(),
                auditReport.issues()
        );
    }

    private String renderAnswer(CitedAnswer answer, AnswerAuditReport auditReport) {
        if (answer == null) {
            return "没有找到足够依据，无法可靠回答。";
        }
        StringBuilder builder = new StringBuilder();
        if (answer.statements().isEmpty()) {
            builder.append("没有找到足够依据，无法可靠回答。");
        } else {
            for (CitedStatement statement : answer.statements()) {
                builder.append("- ")
                        .append(statement.text());
                if (statement.evidenceIds().isEmpty()) {
                    builder.append(" [未找到依据]");
                } else {
                    builder.append(" [")
                            .append(String.join(", ", statement.evidenceIds()))
                            .append("]");
                }
                builder.append("\n");
            }
        }
        if (!answer.limitations().isEmpty()) {
            builder.append("\n依据限制：\n");
            for (String limitation : answer.limitations()) {
                builder.append("- ").append(limitation).append("\n");
            }
        }
        if (auditReport != null && !auditReport.passed()) {
            builder.append("\n审查提示：")
                    .append(TextUtil.trimToDefault(auditReport.overallComment(), "答案审查未完全通过。"));
        }
        return TextUtil.trimToDefault(builder.toString(), "合成器没有返回有效回答。");
    }
}
