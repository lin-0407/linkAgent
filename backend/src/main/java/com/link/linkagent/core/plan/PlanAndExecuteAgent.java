package com.link.linkagent.core.plan;

import com.link.linkagent.core.AgentRunResult;
import com.link.linkagent.core.AgentStep;
import com.link.linkagent.core.Observation;
import com.link.linkagent.core.ToolCall;
import com.link.linkagent.tool.ToolExecutor;
import com.link.linkagent.tool.ToolRegistry;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plan-and-Execute Agent —— 将 ReAct 的”边想边做”改为”先规划、再执行、再回答”的三段式显式流程。
 * <p>
 * <b>架构定位：</b>这是 PaE 模式的顶层编排器，负责串联 Planner → Executor(含 Replanner) → Synthesizer 三个子组件。
 * 它本身不做推理或工具调用，只做流程控制：生成计划、逐步执行、失败重新规划、合成最终答案。
 * <p>
 * <b>为什么需要 PaE 模式：</b>ReAct 擅长灵活多步推理，但在需要全局视野的任务（如对比多个 B 站视频数据）
 * 中，先想好整体策略再分步取证，效率更高、容错更好。两种模式通过 {@code AgentExecutionModeRouter} 按需切换。
 * <p>
 * <b>设计决策：</b>工具执行直接复用 {@code ToolExecutor}，不另写一套工具执行逻辑——保证任何模式下的工具调用
 * 享有相同的错误处理、超时控制和可观测性。
 *
 * @see AgentPlanner 初始计划生成器
 * @see AgentReplanner 失败后的重规划器
 * @see AgentAnswerSynthesizer 执行结果的答案合成器
 */
@Component
public class PlanAndExecuteAgent {

    /**
     * 重规划最大尝试次数。设置上限是为了防止 Planner/Replanner 在失败路径上来回振荡——
     * 每次重规划都消耗一次 LLM 调用（Token 成本 + 延迟），2 次是响应及时性与容错深度之间的折中：
     * 第 1 次尝试通常是模型换个表达方式就过，第 2 次是最后的修正机会，再失败就接受当前结果输出可能不完美但有用的答案。
     */
    private static final int MAX_REPLAN_ATTEMPTS = 2;

    private final AgentPlanner agentPlanner;
    private final AgentReplanner agentReplanner;
    private final ToolExecutor toolExecutor;
    private final ToolRegistry toolRegistry;
    private final AgentAnswerSynthesizer answerSynthesizer;

    /**
     * 构造 PaE 编排器，注入计划、重规划、执行、合成四大子组件。
     * <p>
     * {@code ToolRegistry} 虽然不在 executeStep 中直接查询，但在 executeStep 的"工具是否存在"校验中用到，
     * 这是防止 LLM 编造不存在的工具名导致执行期 NPE 的安全网。
     */
    public PlanAndExecuteAgent(AgentPlanner agentPlanner,
                               AgentReplanner agentReplanner,
                               ToolExecutor toolExecutor,
                               ToolRegistry toolRegistry,
                               AgentAnswerSynthesizer answerSynthesizer) {
        this.agentPlanner = agentPlanner;
        this.agentReplanner = agentReplanner;
        this.toolExecutor = toolExecutor;
        this.toolRegistry = toolRegistry;
        this.answerSynthesizer = answerSynthesizer;
    }

    /**
     * 执行一次完整的 Plan-and-Execute 流程，返回最终答案和完整执行追踪。
     * <p>
     * <b>算法流程（4 段式）：</b>
     * <ol>
     * <li><b>规划（Plan）：</b>Planner 根据对话上下文和用户消息生成多步执行计划</li>
     * <li><b>执行（Execute）：</b>按顺序执行计划步骤，步骤失败时触发重规划重排剩余步骤</li>
     * <li><b>记录（Trace）：</b>将所有执行记录转为 AgentStep 列表，供前端展示和 Langfuse 埋点</li>
     * <li><b>合成（Synthesize）：</b>Synthesizer 将分散的执行结果归纳为用户可读的最终回答</li>
     * </ol>
     *
     * @param conversationContext 对话历史上下文（含长期/短期记忆拼装后的字符串）
     * @param userMessage         用户当前输入
     * @return 最终答案 + 停止原因 + 步骤追踪 + 计划执行轨迹
     */
    public AgentRunResult run(String conversationContext, String userMessage) {
        AgentPlan plan = agentPlanner.plan(conversationContext, userMessage);
        PlanExecutionResult executionResult = executePlan(plan, conversationContext, userMessage);
        List<PlanStepExecution> executions = executionResult.executions();
        List<AgentStep> steps = toAgentSteps(executions);
        AgentPlanTrace trace = AgentPlanTrace.from(plan, executions);
        String stopReason = resolveStopReason(plan, executionResult);
        String finalAnswer = answerSynthesizer.synthesizePlanResult(conversationContext, userMessage, plan, executions);
        return AgentRunResult.planExecute(finalAnswer, stopReason, steps, trace);
    }

    /**
     * 计划执行主循环 —— 这是 PaE 模式的核心驱动逻辑。
     * <p>
     * <b>算法思路（贪心 + 失败回跳）：</b>
     * <ol>
     * <li>从计划中 pop 出下一个步骤，逐个执行</li>
     * <li>步骤成功 → 记录成功 ID（供后续步骤做依赖校验）</li>
     * <li>步骤失败 → 收集失败指纹 → 调用 Replanner 重排剩余步骤 → 替换 remainingSteps 后继续循环</li>
     * <li>重规划命中上限或 Replanner 返回兜底结果 → 直接跳过当前失败继续执行下一步（不阻塞整体流程）</li>
     * </ol>
     * <p>
     * <b>为什么用 {@code remainingSteps.remove(0)} 而非 for-each：</b>重规划后 holdingSteps 可能被整体替换为新的步骤列表，
     * for-each 在迭代中修改集合会抛 ConcurrentModificationException，用 while+pop 更直观且安全。
     * <p>
     * <b>兜底语义：</b>如果 Planner 未生成任何步骤（空计划），直接返回空执行结果——由调用方 {@link #run} 的输出语义兜底。
     *
     * @param plan                Planner 生成的初始计划
     * @param conversationContext 对话上下文
     * @param userMessage         用户消息
     * @return 包含所有步骤执行记录、恢复成功标记、重规划次数的结果对象
     */
    private PlanExecutionResult executePlan(AgentPlan plan, String conversationContext, String userMessage) {
        // 所有已执行步骤的记录（含成功/失败/跳过），全场累加不清空
        List<PlanStepExecution> executions = new ArrayList<>();
        // 记录哪些失败步骤后来被重规划恢复的 ID，用于 stopReason 统计时排除"已恢复的错误"
        List<Integer> recoveredFailureStepIds = new ArrayList<>();
        // 已成功的步骤 ID 集合，用于后续步骤的依赖校验
        Set<Integer> successStepIds = new HashSet<>();
        // 已失败的工具方案指纹（action + actionInput 的组合），防止 Replanner 重复推荐同一失败方案
        Set<String> failedFingerprints = new HashSet<>();
        int replanAttempts = 0;

        // 空指针防护 + 空计划兜底：没有可执行步骤时尽早返回，避免后续逻辑在空集合上操作
        if (plan == null || plan.steps().isEmpty()) {
            return new PlanExecutionResult(executions, recoveredFailureStepIds, replanAttempts);
        }

        // 用可变的副本作为待执行队列，方便重规划时整体替换
        List<AgentPlanStep> remainingSteps = new ArrayList<>(plan.steps());
        while (!remainingSteps.isEmpty()) {
            // 每次从队头取出下一步——保证步骤按原始顺序执行，重规划后也是从头开始
            AgentPlanStep step = remainingSteps.remove(0);
            PlanStepExecution execution = executeStep(step, successStepIds, failedFingerprints);
            executions.add(execution);

            if (execution.status() == PlanStepStatus.SUCCESS) {
                successStepIds.add(execution.stepId());
                continue; // 步骤成功，直接进入下一个步骤
            }

            // 不满足重规划条件（非失败、已达上限）→ 跳过当前失败，不阻塞剩余步骤执行
            if (!shouldReplan(execution) || replanAttempts >= MAX_REPLAN_ATTEMPTS) {
                continue;
            }

            // 记录失败指纹后立即请求重规划，保证 Replanner 能感知最新的失败模式
            failedFingerprints.add(fingerprint(step));
            PlanExecutionState state = new PlanExecutionState(
                    plan.objective(),         // 原目标不变
                    executions,               // 全量已执行记录
                    remainingSteps,           // 当前剩余的待执行步骤
                    List.copyOf(failedFingerprints) // 防御性拷贝，防止后续修改污染 state
            );
            AgentPlan replannedPlan = agentReplanner.replan(conversationContext, userMessage, state);

            // Replanner 兜底（提示词缺失 / LLM 调用失败 / 返回空对象）：保留原剩余计划继续执行
            if (AgentReplanner.FALLBACK_RATIONALE.equals(replannedPlan.rationale())) {
                replanAttempts++;
                continue;
            }

            // 重规划成功后需要做两件事：① 给新步骤重新编 ID，避免与已执行的 ID 冲突；
            // ② 过滤掉已被成功步骤覆盖的冗余步骤（Replanner 可能不小心又包含已完成步骤）
            AgentPlan reindexedPlan = AgentPlanNormalizer.reindexRemainingSteps(
                    replannedPlan,
                    nextStepId(plan, executions, remainingSteps),
                    successStepIds
            );
            remainingSteps = new ArrayList<>(reindexedPlan.steps());

            // 如果重规划后仍有后续步骤，说明当前失败被"修复"了（有替代路线）
            if (!remainingSteps.isEmpty()) {
                recoveredFailureStepIds.add(execution.stepId());
            }
            replanAttempts++;
        }
        return new PlanExecutionResult(executions, recoveredFailureStepIds, replanAttempts);
    }

    /**
     * 执行单个计划步骤，依次经过 4 道校验后调用工具，返回执行结果。
     * <p>
     * <b>校验顺序与设计理由（fail-fast 模式）：</b>
     * <ol>
     * <li><b>依赖检查：</b>前置步骤未成功时直接 SKIP（而非 FAIL）——因为这里不是步骤本身的问题，
     * 而是上游失败导致的连锁反应，标记 SKIP 有助于 stopReason 区分"真失败"和"被波及"</li>
     * <li><b>动作不为空：</b>计划步骤缺 action 是 Planner 层面的错误，直接 FAIL</li>
     * <li><b>指纹去重：</b>防止 Replanner 重复已失败的同一工具方案——如果 Replanner 又生成相同方案，
     * 说明它在失败路径上震荡，直接拦截避免一次又一次重复调用相同工具浪费资源</li>
     * <li><b>工具存在性：</b>防止 LLM 编造工具名导致 NPE——在调用工具前先查 ToolRegistry，
     * 这是计划·执行模式与 ReAct 模式最重要的区别之一（ReAct 是 LLM 自己选工具，工具不存在时靠 Observation 反馈纠正）</li>
     * </ol>
     * <p>
     * <b>工具执行后的二次校验：</b>
     * <ul>
     * <li>工具返回以 "Error:" 开头 → 判定为工具层错误（非预期异常）</li>
     * <li>工具返回为空但计划期望有观察结果 → 说明工具可能静默失败，视为 FAIL</li>
     * </ul>
     *
     * @param step               当前要执行的计划步骤
     * @param successStepIds     已成功步骤的 ID 集合，用于依赖检查
     * @param failedFingerprints 已失败的工具方案指纹集合，用于去重拦截
     * @return 步骤执行结果（SUCCESS / FAILED / SKIPPED）
     */
    private PlanStepExecution executeStep(AgentPlanStep step, Set<Integer> successStepIds, Set<String> failedFingerprints) {
        // 1. 依赖校验：前置步骤未成功时不继续执行，避免后续工具基于缺失前置事实产生误导性结果
        if (!successStepIds.containsAll(step.dependsOn())) {
            return toExecution(step, PlanStepStatus.SKIPPED, null, "前置步骤未成功，已跳过本步。");
        }
        // 2. 动作不为空校验：计划步骤缺少 action 说明 Planner 输出有误
        if (TextUtil.isBlank(step.action())) {
            return toExecution(step, PlanStepStatus.FAILED, null, "计划步骤缺少 action。");
        }
        // 3. 指纹去重：Replanner 不应重复已经失败的同一工具方案，直接拦截能防止重规划在失败路径上来回振荡
        if (failedFingerprints.contains(fingerprint(step))) {
            return toExecution(step, PlanStepStatus.FAILED, null, "Replanner 重复了已失败的工具方案：" + fingerprint(step));
        }
        // 4. 工具存在性校验：防止 LLM 编造不存在的工具名
        if (toolRegistry.getTool(step.action()) == null) {
            return toExecution(step, PlanStepStatus.FAILED, null, "计划引用了不存在的工具：" + step.action());
        }
        // 5. 执行工具
        Observation observation = toolExecutor.execute(new ToolCall(step.action(), TextUtil.trimToDefault(step.actionInput(), "")));
        String result = observation == null ? null : observation.result();
        // 6. 结果校验——工具层错误（Error: 前缀是 ToolExecutor 的统一错误格式标记）
        if (isToolError(result)) {
            return toExecution(step, PlanStepStatus.FAILED, result, result);
        }
        // 7. 空结果 + 期望有观察 → 工具静默失败，未满足预期
        if (TextUtil.isBlank(result) && TextUtil.hasText(step.expectedObservation())) {
            return toExecution(step, PlanStepStatus.FAILED, result, "工具返回为空，未满足预期观察：" + step.expectedObservation());
        }
        return toExecution(step, PlanStepStatus.SUCCESS, result, null);
    }

    /**
     * 判断当前失败是否触发重规划。
     * <p>
     * 当前策略：仅 FAILED 才触发重规划，SKIPPED（依赖失败导致）不触发——因为 SKIPPED 不是步骤本身有问题，
     * 而是上游失败，Replanner 重新安排剩余步骤就能解决。
     *
     * @param execution 步骤执行结果
     * @return 是否应该触发重规划
     */
    private boolean shouldReplan(PlanStepExecution execution) {
        return execution.status() == PlanStepStatus.FAILED;
    }

    /**
     * 生成步骤指纹——{@code action} + "::" + {@code actionInput} 的组合。
     * <p>
     * 指纹用于两步去重：(1) 将失败方案的指纹加入 {@code failedFingerprints} 集合；
     * (2) Replanner 在重规划时从剩余步骤中发现相同指纹的方案直接拦截。
     * 指纹粒度定在 action+actionInput 而非仅 action，因为同一个工具用不同参数可能是不同的策略路径。
     * <p>
     * 为什么不用 hashCode：集合存储用 Set<String>，跨 JVM 重启无影响，且 action+actionInput
     * 长度可控（通常 < 500 字符），字符串去重完全够用。
     *
     * @param step 计划步骤
     * @return 格式为 "action::actionInput" 的去重指纹
     */
    private String fingerprint(AgentPlanStep step) {
        return TextUtil.trimToDefault(step.action(), "") + "::" + TextUtil.trimToDefault(step.actionInput(), "");
    }

    /**
     * 为重规划后的新步骤分配起始 ID（当前已出现的最大 ID + 1），保证全局 ID 连续且不冲突。
     * <p>
     * 遍历三个来源取 max：(1) 原始计划步骤 (2) 已执行记录 (3) 原剩余步骤——这三个来源可能包含
     * 重规划前不同时间点的 ID，取全局最大值 + 1 确保新编号不与任何已用 ID 冲突。
     *
     * @param plan           原始计划
     * @param executions     已执行记录
     * @param remainingSteps 被替换前的剩余步骤
     * @return 下一个可用步骤 ID
     */
    private int nextStepId(AgentPlan plan, List<PlanStepExecution> executions, List<AgentPlanStep> remainingSteps) {
        int maxId = 0;
        if (plan != null) {
            for (AgentPlanStep step : plan.steps()) {
                maxId = Math.max(maxId, step.id());
            }
        }
        for (PlanStepExecution execution : executions) {
            maxId = Math.max(maxId, execution.stepId());
        }
        for (AgentPlanStep step : remainingSteps) {
            maxId = Math.max(maxId, step.id());
        }
        return maxId + 1;
    }

    private PlanStepExecution toExecution(AgentPlanStep step, PlanStepStatus status, String observation, String errorMessage) {
        return new PlanStepExecution(
                step.id(),
                step.description(),
                step.action(),
                step.actionInput(),
                step.dependsOn(),
                step.expectedObservation(),
                status,
                observation,
                errorMessage
        );
    }

    /**
     * 判断工具返回是否为错误结果。"Error:" 前缀是 ToolExecutor 层统一错误标签——
     * 工具执行过程中如果捕获到异常，会将异常信息包装为 "Error: xxx" 格式返回，而非抛异常打断主流程。
     *
     * @param result 工具返回文本
     * @return true 表示工具执行出错
     */
    private boolean isToolError(String result) {
        return TextUtil.trimToDefault(result, "").startsWith("Error:");
    }

    /**
     * 将重规划执行记录转为前端展示用的 AgentStep 列表。
     * <p>
     * PlanStepExecution 与 AgentStep 的差异：前者是 PaE 模式的内部结构（含依赖/状态/期望观察等），
     * 后者是面向用户展示和 Langfuse 埋点的通用格式。这里做一次单向转换，隐藏内部实现细节。
     *
     * @param executions PaE 执行记录列表
     * @return 用户可见的 Agent 步骤列表
     */
    private List<AgentStep> toAgentSteps(List<PlanStepExecution> executions) {
        List<AgentStep> steps = new ArrayList<>();
        int index = 1;
        for (PlanStepExecution execution : executions) {
            steps.add(new AgentStep(
                    index++,
                    "计划执行：" + execution.description(),
                    execution.action(),
                    execution.actionInput(),
                    TextUtil.trimToDefault(execution.observation(), execution.errorMessage())
            ));
        }
        return steps;
    }

    /**
     * 根据计划执行结果生成人类可读的停止原因。
     * <p>
     * 策略：(1) 空计划 → 说明 Planner 未能生成步骤；(2) 有失败/跳过步骤 → 统计数量给出概括；
     * (3) 全部成功 → 返回 null（表示正常终止，非异常）。
     * <p>
     * 统计失败数时排除已被重规划恢复的步骤——这些步骤虽然原始执行失败，但后续通过替代路径达成了目标，
     * 对用户来说不算真正的"失败"。
     *
     * @param plan            原始计划
     * @param executionResult 执行结果（含已恢复步骤标记）
     * @return 停止原因描述，正常终止时返回 null
     */
    private String resolveStopReason(AgentPlan plan, PlanExecutionResult executionResult) {
        if (plan == null || plan.steps().isEmpty()) {
            return "Planner 未生成可执行步骤，已直接进入合成兜底。";
        }
        Set<Integer> recoveredIds = new HashSet<>(executionResult.recoveredFailureStepIds());
        long failedCount = executionResult.executions().stream()
                .filter(execution -> execution.status() == PlanStepStatus.FAILED)
                .filter(execution -> !recoveredIds.contains(execution.stepId()))
                .count();
        long skippedCount = executionResult.executions().stream()
                .filter(execution -> execution.status() == PlanStepStatus.SKIPPED)
                .count();
        if (failedCount == 0 && skippedCount == 0) {
            return null;
        }
        return "计划执行未完全成功：失败 " + failedCount + " 步，跳过 " + skippedCount + " 步。";
    }

    /**
     * 计划执行结果的内部聚合记录。
     * <p>
     * 使用 record 而非普通 class 有两个原因：(1) 结果对象在用完后即丢弃，不可变性是理想语义；
     * (2) compact constructor 保证列表字段永不为 null，下游遍历时无需额外空指针检查。
     *
     * @param executions               所有已执行步骤的记录（含成功、失败、跳过），按时间顺序排列
     * @param recoveredFailureStepIds  被重规划恢复的失败步骤 ID 列表（用于 stopReason 统计去重）
     * @param replanAttempts           重规划累计尝试次数（含兜底不计数的尝试）
     */
    private record PlanExecutionResult(
            List<PlanStepExecution> executions,
            List<Integer> recoveredFailureStepIds,
            int replanAttempts
    ) {

        /**
         * Compact constructor: 将 null 的列表字段替换为空列表，
         * 保证下游代码永远看到的是合法集合而非 null 引用。
         */
        private PlanExecutionResult {
            executions = executions == null ? List.of() : List.copyOf(executions);
            recoveredFailureStepIds = recoveredFailureStepIds == null ? List.of() : List.copyOf(recoveredFailureStepIds);
        }
    }
}
