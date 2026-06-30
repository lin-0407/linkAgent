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
 * Plan-and-Execute 的重规划 Agent —— 在步骤执行失败后重新安排剩余路线。
 * <p>
 * <b>架构定位：</b>Replanner 是 PaE 模式的"韧性组件"——它不是必走路径，只在步骤失败时介入。
 * 它不会推翻已经成功执行的事实（successStepIds 由调用方过滤），只针对剩余步骤重新规划替代路线。
 * <p>
 * <b>设计权衡：</b>Replanner 输出失败的兜底策略是"保留原剩余计划继续执行"而非"中断流程"——
 * 因为 Replanner 本身是一个 LLM 调用，有提示词缺失、结构化解析失败、模型幻觉等风险。
 * 把它设计为"增强而非依赖"的组件，保证即便 Replanner 挂了，PaE 主流程仍能以低质量（但非完全失败）的方式继续。
 * <p>
 * <b>与 Planner 的关系：</b>Planner 从零生成计划，Replanner 基于已执行状态修正剩余计划。
 * 两者共享同一套 PromptService（各自的提示词模板不同：agent_plan_execute_planner vs agent_plan_execute_replanner）
 * 但业务语义不同——Planner 做整体规划，Replanner 做局部修正。
 *
 */
@Component
public class AgentReplanner {

    /**
     * Replanner 执行失败的兜底 rationale 文本。
     * <p>
     * 调用方通过 {@code FALLBACK_RATIONALE.equals(replannedPlan.rationale())} 判断 Replanner 是否正常产出结果。
     * 使用常量而非魔法字符串有三重价值：(1) 编译期唯一引用，全局搜索即可找到所有判断点；
     * (2) 常量语义自描述，不需要注释解释 "Replanner 执行失败" 这个字符串的含义；
     * (3) 单点维护，未来改文案只需改一处。
     */
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

    /**
     * 基于当前执行现场重新规划剩余步骤。
     * <p>
     * <b>输入：</b>{@code PlanExecutionState} 包含原目标、已执行记录、剩余步骤、已失败指纹——给 LLM 提供完整现场信息，
     * 让它知道哪些路径走过了、哪些走不通了，从而产出合理的替代方案。
     * <p>
     * <b>输出处理：</b>
     * <ol>
     * <li>正常返回 → 调用方用 {@link AgentPlanNormalizer#reindexRemainingSteps} 重新编 ID 并过滤已完成步骤</li>
     * <li>异常兜底 → 返回 rational 为 {@code FALLBACK_RATIONALE} 的兜底计划（保留原剩余步骤）</li>
     * </ol>
     * <p>
     * <b>为什么不把 null 检查移到外层：</b>chatStructured 在解析失败时会抛异常，如果在外层 catch 并返回 null，
     * 调用方又要加一层 null 检查。用 FALLBACK_RATIONALE 常量做标记值，让成功/失败走同一种返回类型，
     * 调用方只需一个 equals 判断代码更干净。
     *
     * @param conversationContext 对话上下文（含长期/短期记忆拼接后的文本）
     * @param userMessage         用户原始输入
     * @param state               当前执行状态快照
     * @return 重规划后的计划（正常）或兜底计划（Replanner 执行失败时）——两者通过 rationale 字段区分
     */
    public AgentPlan replan(String conversationContext, String userMessage, PlanExecutionState state) {
        try {
            // 获取全量工具清单 → Replanner 需要知道有哪些工具可用，才能生成合理的替代步骤
            Collection<Tool> tools = toolRegistry.getAllTools();
            // 用 Replanner 专用提示词模板（区别于 Planner 的 agent_plan_execute_planner.system）
            String systemPrompt = promptService.render("agent_plan_execute_replanner.system",
                    Map.of("toolList", AgentToolPromptFormatter.format(tools)));
            // 结构化输出直接映射到 AgentPlan 类，省去文本→JSON 的正则解析步骤
            AgentPlan plan = llmService.chatStructured(
                    systemPrompt,
                    buildReplanUserMessage(conversationContext, userMessage, state),
                    AgentPlan.class
            );
            // normalize 负责处理 LLM 返回 null/空 steps 等边界情况，输出安全的 AgentPlan 对象
            return AgentPlanNormalizer.normalize(plan, "Replanner 返回空对象");
        } catch (RuntimeException exception) {
            // Replanner 是韧性增强，不应该因为提示词缺失或结构化解析失败让 PaE 主流程直接中断。
            // 兜底方案：保留原剩余步骤继续执行——虽然原路线可能导致同样失败，
            // 但至少比直接抛 500 强，用户能看到"部分进展 + 部分失败"的结果而非空白。
            log.warn("PaE 重规划失败，保留原剩余计划。error={}", exception.getMessage());
            return new AgentPlan(
                    TextUtil.trimToDefault(state.objective(), "沿用原计划"),
                    state.remainingSteps(),
                    FALLBACK_RATIONALE,
                    "未能重新评估剩余诉求"
            );
        }
    }

    /**
     * 构造发给 Replanner LLM 的用户消息，将执行现场信息以结构化 Markdown 格式呈现。
     * <p>
     * <b>提示词设计要点：</b>
     * <ul>
     * <li>"【用户当前请求】" + "【可参考上下文】" → 让 Replanner 不丢失最初的任务目标</li>
     * <li>"【原目标】" → 提醒 Replanner 不要偏离 Planner 设定的总方向</li>
     * <li>"【已执行步骤】" → 让 Replanner 知道哪些已经做了、哪些数据已经获取，避免重复工作</li>
     * <li>"【已失败方案指纹】" → 这是最关键的部分——告诉 Replanner 哪些具体方案走不通了，
     * 让它避免再次推荐相同的工具+参数组合</li>
     * </ul>
     * <p>
     * 要求 1~4 是给 LLM 的行为约束：不重复已有成果、不重复已知失败、使用合法工具、允许放弃——
     * 这四条合起来保证 Replanner 的输出在一个有边界的搜索空间内，降低越界风险。
     *
     * @param conversationContext 对话上下文
     * @param userMessage         用户当前输入
     * @param state               执行状态快照
     * @return 格式化的中文提示词文本
     */
    private String buildReplanUserMessage(String conversationContext, String userMessage, PlanExecutionState state) {
        // 使用 Java 17+ text block + formatted() 保持提示词模板的可读性
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
