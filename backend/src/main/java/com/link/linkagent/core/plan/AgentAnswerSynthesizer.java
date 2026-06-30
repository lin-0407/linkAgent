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
 * Agent 答案合成器 —— 将分散的执行事实（Plan 步骤执行结果或多 Worker 输出）合成用户可读的最终回答。
 * <p>
 * <b>架构定位：</b>Synthesizer 是 PaE / Multi-Agent / Worker Summary 三种路径的"统一出口"。
 * 无论 Agent 内部用了哪种执行模式，最终呈现给用户的都是经过同一流程（生成 → 审查 → 重写）的带引用回答。
 * <p>
 * <b>为什么需要统一出口：</b>
 * <ul>
 * <li><b>一致性：</b>Plan/Worker 产生的是离散事实，如果各路径拼自由文本会出现风格不一致、引用丢失等问题</li>
 * <li><b>可溯源性：</b>每条陈述绑定证据 ID（evidenceId），用户可以追查到"哪个工具/Worker 产生了这个结论"</li>
 * <li><b>幻觉防护：</b>AnswerAuditor 审查后，虚构证据 ID 的陈述会被过滤，证据不充分的陈述会标记 limitations</li>
 * </ul>
 * <p>
 * <b>核心流程（生成 → 审查 → 重写循环）：</b>
 * <ol>
 * <li><b>生成：</b>LLM 基于证据列表生成 CitedAnswer（含带引用的 statements 和 limitations）</li>
 * <li><b>审查：</b>AnswerAuditor 校验每条 statement 的证据有效性、事实准确性、充分性</li>
 * <li><b>重写：</b>未通过审查则将审查报告反馈给 LLM 重写，最多重写 {@value MAX_AUDIT_REWRITE_ROUNDS} 轮</li>
 * <li><b>渲染：</b>将结构化 CitedAnswer 转为给用户展示的纯文本格式</li>
 * </ol>
 *
 * @see AgentAnswerAuditor 答案审查器，校验引用有效性和事实准确性
 * @see AgentEvidence 证据对象，封装执行结果中的可引用事实
 */
@Component
public class AgentAnswerSynthesizer {

    /**
     * 审查-重写循环的最大轮数。LLM 生成的答案可能存在幻觉引用或证据不足的问题，
     * Auditor 会将问题反馈给 LLM 重写。2 轮是"修正充分性"与"响应延迟"的折中——
     * 第 1 轮修复多数幻觉引用和明显的事实矛盾，第 2 轮微调措辞和补充 limitations，
     * 之后仍不通过则直接输出当前最佳结果（附带审查提示），不无限循环沉淀成本。
     */
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

    /**
     * PaE 模式的答案合成入口——从计划步骤执行记录中提取证据，合成最终回答。
     * <p>
     * 与 Multi-Agent 合成共享同一核心流程 {@link #synthesizeWithEvidence}，差异在于：
     * (1) 使用 PaE 专用提示词模板 "agent_plan_execute_synthesizer.system"；
     * (2) 用户消息拼接计划结构 + 步骤执行结果；
     * (3) 证据来源为计划步骤的 observation（成功步骤的观察结果 + 失败步骤的错误信息）。
     *
     * @param conversationContext 对话上下文
     * @param userMessage         用户当前输入
     * @param plan                原始计划（含目标、依据、步骤列表）
     * @param executions          所有步骤的执行记录
     * @return 带引用标记的最终回答文本
     */
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

    /**
     * Multi-Agent 模式的答案合成入口（结构化 WorkerTrace 版本）。
     * <p>
     * 从每个 Worker 的 {@link AgentWorkerTrace} 中提取结构化的 {@link AgentEvidence} 列表，
     * Worker 的 errorMessage 也会被转为低置信度的 SYSTEM_LIMITATION 类型证据。
     *
     * @param conversationContext 对话上下文
     * @param userMessage         用户当前输入
     * @param workerTraces        所有 Worker 的执行追踪记录
     * @return 带引用标记的最终回答文本
     */
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

    /**
     * Multi-Agent 模式的答案合成入口（遗留 Worker 摘要版本）。
     * <p>
     * 这个重载用于兼容旧版 Worker 输出格式——旧版 Worker 不产生结构化的 {@code AgentWorkerTrace}，
     * 只有一个自由文本摘要。将整个摘要包装为一个低置信度（0.5）的 WORKER_REASONING 类型证据。
     * 新代码应使用 {@link #synthesizeMultiAgentResult(String, String, List)} 的 WorkerTrace 版本。
     *
     * @param conversationContext 对话上下文
     * @param userMessage         用户当前输入
     * @param workerSummary       Worker 的自由文本摘要
     * @return 带引用标记的最终回答文本
     */
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

    /**
     * 核心合成流程：生成答案 → 规范化引用 ID → 审查 → 重写循环 → 渲染输出。
     * <p>
     * <b>为什么需要审查-重写循环而不直接信任 LLM 输出：</b>LLM 可能编造不存在的 evidenceId、
     * 基于低置信度证据给出过于确定的结论、或在无证据的情况下做推测。
     * AnswerAuditor 作为第二道防线，对照证据列表逐条校验答案质量。
     * <p>
     * <b>重写循环的终止条件：</b>(1) 审查通过 → 直接输出；(2) 达到 {@code MAX_AUDIT_REWRITE_ROUNDS} 上限 →
     * 输出当前结果并附带审查提示（auditReport 未通过但也不会继续重写了）。
     *
     * @param systemPromptKey    系统提示词的 PromptService key
     * @param originalUserMessage 用户原始输入（供 Auditor 评估答案是否答非所问）
     * @param synthesisUserMessage 拼接了上下文/计划/执行结果的用户消息
     * @param evidences           从执行结果中提取的证据列表
     * @return 经过审查过滤的最终回答文本
     */
    private String synthesizeWithEvidence(String systemPromptKey, String originalUserMessage,
                                          String synthesisUserMessage, List<AgentEvidence> evidences) {
        // 无证据兜底：没有可引用的证据时直接返回"无法回答"，不调用 LLM 浪费 Token
        if (evidences == null || evidences.isEmpty()) {
            return renderAnswer(CitedAnswer.empty("没有可引用证据，无法可靠回答。"), null);
        }
        // 第一轮：LLM 生成答案 → 规范化引用 ID（过滤虚构 ID）
        CitedAnswer answer = normalizeAnswerEvidenceIds(requestCitedAnswer(systemPromptKey, synthesisUserMessage, evidences, null), evidences);
        AnswerAuditReport auditReport = answerAuditor.audit(originalUserMessage, answer, evidences);
        int rewriteRound = 0;
        // 审查-重写循环：将上一轮审查反馈注入用户消息，引导 LLM 修正
        while (!auditReport.passed() && rewriteRound < MAX_AUDIT_REWRITE_ROUNDS) {
            answer = normalizeAnswerEvidenceIds(requestCitedAnswer(systemPromptKey, synthesisUserMessage, evidences, auditReport), evidences);
            auditReport = answerAuditor.audit(originalUserMessage, answer, evidences);
            rewriteRound++;
        }
        return renderAnswer(answer, auditReport);
    }

    /**
     * 调用 LLM 生成带引用的结构化答案（CitedAnswer JSON）。
     * <p>
     * <b>提示词设计策略：</b>
     * <ul>
     * <li>【可用证据】列出所有证据及其 ID/来源/置信度——让 LLM 有明确的"事实池"可供引用</li>
     * <li>【输出要求】强调"每个事实性 statement 必须引用 evidenceIds"——这是防幻觉的第一道防线</li>
     * <li>【上一轮审查反馈】仅在重写轮有值——将 Auditor 发现的问题反馈给 LLM 做修正</li>
     * </ul>
     * <p>
     * <b>为什么用 {@code chatStructured} 而非 {@code chat}：</b>CitedAnswer 是固定的 JSON 结构，
     * 用结构化输出 + 反序列化到 Java Bean 比正则解析更可靠且更少出错。
     *
     * @param systemPromptKey      系统提示词 key
     * @param synthesisUserMessage 拼接了上下文的用户消息
     * @param evidences            可用证据列表
     * @param previousAudit        上一轮审查报告（首轮为 null）
     * @return LLM 生成的带引用答案；LLM 返回 null 时返回空 CitedAnswer 兜底
     */
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
        // 兜底：LLM 返回 null 时（如结构化解析失败被 catch）不抛 NPE，而是返回空答案
        return answer == null ? CitedAnswer.empty("合成器返回空对象，无法可靠回答。") : answer;
    }

    /**
     * 规范化答案中的证据 ID 引用，过滤掉 LLM 编造的引用并转置到 limitations。
     * <p>
     * <b>为什么需要这道过滤：</b>LLM 可能会出现证据 ID 幻觉——引用了一个实际不存在的 evidenceId。
     * 这一步将 statements 中的引用与实际证据列表做交叉比对，删除无有效引用的陈述，
     * 并将被删除的陈述内容记录到 limitations 中（让用户知道"有一句话因为没证据被拿掉了"）。
     * <p>
     * <b>不允许放宽：</b>即使只有部分 ID 无效也应保留。因为有效 ID 对应的内容可能仍成立——
     * normalizeStatement 内部会保留有效 ID 子集并继续保留该陈述。
     *
     * @param answer    LLM 生成的原始答案
     * @param evidences 实际可用的证据列表
     * @return 经过 ID 校验和过滤的答案
     */
    private CitedAnswer normalizeAnswerEvidenceIds(CitedAnswer answer, List<AgentEvidence> evidences) {
        if (answer == null) {
            return CitedAnswer.empty("合成器返回空对象，无法可靠回答。");
        }
        // 构建合法证据 ID 白名单（Set 保证 O(1) 查找）
        Set<String> allowedEvidenceIds = evidences.stream()
                .map(AgentEvidence::evidenceId)
                .collect(java.util.stream.Collectors.toSet());
        // 收集被删除的陈述文本，追加到 limitations 中——让用户感知到过滤操作
        List<String> droppedStatements = new java.util.ArrayList<>();
        // 逐条过滤 statement：无效 ID 的陈述被移除，部分有效 ID 的陈述保留有效子集
        List<CitedStatement> normalizedStatements = answer.statements().stream()
                .map(statement -> normalizeStatement(statement, allowedEvidenceIds, droppedStatements))
                .filter(statement -> statement != null)
                .toList();
        // 合并原 limitations + 被过滤掉的内容
        List<String> limitations = new java.util.ArrayList<>(answer.limitations());
        limitations.addAll(droppedStatements);
        return new CitedAnswer(normalizedStatements, limitations);
    }

    /**
     * 规范化单条陈述的引用 ID。
     * <p>
     * (1) 过滤掉不在白名单中的 ID（LLM 编造的引用）; (2) 去重（LLM 可能重复引用同一证据）。
     * <p>
     * <b>判决逻辑：</b>全部引用无效 → 删除整条陈述（记录到 droppedStatements）；
     * 部分引用有效 → 保留陈述但只保留有效 ID 子集。
     * 为什么不全部删除：部分引用无效可能是 LLM 记错了 ID 格式（如多了个前缀/后缀），
     * 但 statement 本身的事实内容可能仍成立（由有效 ID 支撑）。
     *
     * @param statement         原始陈述
     * @param allowedEvidenceIds 合法的证据 ID 白名单
     * @param droppedStatements  已删除的陈述收集器（调用方用于追加到 limitations）
     * @return 规范化后的陈述；无有效引用时返回 null
     */
    private CitedStatement normalizeStatement(CitedStatement statement, Set<String> allowedEvidenceIds,
                                              List<String> droppedStatements) {
        // 只保留在白名单中的 ID，同时去重
        List<String> validEvidenceIds = statement.evidenceIds().stream()
                .filter(allowedEvidenceIds::contains)
                .distinct()
                .toList();
        if (validEvidenceIds.isEmpty()) {
            // 证据 ID 必须由代码层过滤，避免模型编造一个看似可信、实际不存在的引用
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

    /**
     * 从计划步骤执行记录中提取证据列表。
     * <p>
     * 成功步骤的 observation 用标准置信度（来自 AgentEvidence.fromPlanStep），
     * 失败步骤的 errorMessage 转为低置信度（0.4）的 SYSTEM_LIMITATION 类型证据——
     * 虽然失败，但错误信息本身对用户理解"为什么没能完全回答"是有价值的。
     *
     * @param executions 所有步骤执行记录
     * @return 证据列表（空列表而非 null）
     */
    private List<AgentEvidence> buildPlanEvidences(List<PlanStepExecution> executions) {
        if (executions == null || executions.isEmpty()) {
            return List.of();
        }
        return executions.stream()
                .map(this::toEvidence)
                .filter(evidence -> evidence != null)
                .toList();
    }

    /**
     * 将单条步骤执行记录转为证据对象。
     * <p>
     * 转换规则：SUCCESS + 有 observation → 标准证据；有 errorMessage → 低置信度错误证据；
     * 其他情况（SKIPPED 或无 observation/errorMessage）→ null（无有效信息可引用）。
     *
     * @param execution 步骤执行记录
     * @return 证据对象；无有效信息时返回 null（由调用方 filter 掉）
     */
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

    /**
     * 从 Multi-Agent Worker 追踪记录中提取证据列表。
     * <p>
     * 使用 {@code LinkedHashMap} + {@code putIfAbsent} 去重——当多个 Worker 产生相同 evidenceId 时
     * 只保留先出现的那个（保留插入顺序对应于 Worker 调度顺序，先调度先收录）。
     * <p>
     * Worker 的 errorMessage 也会转为低置信度（0.35，比 Plan 步骤的 0.4 更低——因为 Worker 错误
     * 可能是 Orchestrator 切分不当导致的，而非工具本身的问题）。
     *
     * @param workerTraces Worker 执行追踪记录列表
     * @return 去重后的证据列表
     */
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

    /**
     * 将 AnswerAuditReport 转为 LLM 可读的反馈文本。
     * <p>
     * 只有审查未通过时才输出详细反馈（通过时简单返回"无。"）；重写轮中这个文本被拼入
     * requestCitedAnswer 的【上一轮审查反馈】区块，引导 LLM 做出针对性修正。
     *
     * @param auditReport 审查报告
     * @return 格式化的审查反馈文本
     */
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

    /**
     * 将结构化 CitedAnswer 渲染为用户可读的纯文本格式。
     * <p>
     * <b>输出格式：</b>每条 statement 以 "- " 开头，后附 [evidenceId1, evidenceId2] 引用标记——
     * 用户可以通过引用 ID 追溯到具体数据来源。无引用的 statement 标记为 [未找到依据]。
     * limitations 和审查提示分别放在末尾，提醒用户当前答案的约束条件。
     *
     * @param answer      LLM 生成的带引用答案
     * @param auditReport 最终审查报告（可能为 null 或 passed）
     * @return 用户可读的纯文本格式回答
     */
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
