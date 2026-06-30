package com.link.linkagent.core.multi;

import com.link.linkagent.core.AgentRunResult;
import com.link.linkagent.core.AgentStep;
import com.link.linkagent.core.plan.AgentAnswerSynthesizer;
import com.link.linkagent.core.plan.AgentPlanStep;
import com.link.linkagent.core.plan.AgentPlanTrace;
import com.link.linkagent.core.plan.PlanStepExecution;
import com.link.linkagent.core.plan.PlanStepStatus;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 多 Agent Orchestrator —— 多 Worker 协同调度的核心编排器。
 *
 * <h3>在架构中的位置</h3>
 * Orchestrator 位于 Planner 和 Synthesizer 之间，承接 Planner 产出的 {@link WorkerPlan}，
 * 按依赖拓扑顺序并发调度各 Worker 执行，最终将各 Worker 的执行轨迹汇总交给 Synthesizer 合成最终答案。
 * 它不直接调用工具，也不处理业务细节——Worker 是完整的 Agent 能力单元。
 *
 * <h3>核心设计决策</h3>
 * <ul>
 *     <li><b>新增 Worker 无需改调度器</b>：Worker 通过 Spring Bean 自动注入 {@code List<WorkerAgent>}，
 *     Orchestrator 只通过 {@link WorkerAgent} 接口契约调度，实现了开闭原则。</li>
 *     <li><b>依赖驱动并发调度</b>：每个 WorkerCall 通过 {@code dependsOn} 声明前置依赖，
 *     Orchestrator 每轮只执行"所有依赖都已成功"的 Worker，被 <b>跳过（Skipped）的前置依赖不算成功</b>，
 *     因此依赖它的 Worker 也会被跳过——保证失败传播的正确性。</li>
 *     <li><b>并发度受控</b>：用固定大小线程池限制最大并发 Worker 数，避免 LLM API 被同时大量调用导致
 *     限流或资源耗尽。默认上限 4，可根据 Worker 数量自动缩小。</li>
 *     <li><b>失败隔离</b>：单个 Worker 异常不会导致整个调度崩溃——通过 {@code exceptionally} 将异常
 *     转为 FAILED 轨迹并继续调度剩余 Worker，让 Synthesizer 基于部分成功结果尽力产出一个答案。</li>
 * </ul>
 *
 * <h3>调度算法概要</h3>
 * <pre>
 * while (还有未执行的 WorkerCall) {
 *     1. 检查依赖：有失败前置依赖的 → 跳过
 *     2. 找就绪集：所有依赖都已成功的 WorkerCall
 *     3. 如果就绪集为空且没有新跳过项 → 存在无法解析的依赖（循环/缺失）→ 全部跳过
 *     4. 并发执行就绪集中的所有 WorkerCall
 *     5. 收集结果，标记完成
 * }
 * </pre>
 */
@Component
public class MultiAgentOrchestrator {

    /**
     * 默认最大并行 Worker 数。这个值不是越大越好：
     * 每个 Worker 内部会调用 LLM，过多的并发 LLM 请求可能触发服务端限流（429），
     * 且会加剧上下文竞争和 Token 消耗。4 是在"并行加速"和"稳定可靠"之间的经验平衡点。
     */
    private static final int DEFAULT_MAX_PARALLEL_WORKERS = 4;

    private final MultiAgentPlanner multiAgentPlanner;
    private final AgentAnswerSynthesizer answerSynthesizer;

    /**
     * Worker 名称到 Worker 实例的索引，用于 O(1) 按名查找 Worker。
     * Worker 按名索引而非按 ID 索引，因为 Planner 产出的 WorkerCall 中引用的是 Worker 名称而非实例引用。
     */
    private final Map<String, WorkerAgent> workerMap;

    /**
     * 按名称排序的 Worker 列表。排序是为了保证 Planner 看到的 Worker 顺序稳定，
     * 避免每次 Bean 注入顺序不同导致 Planner 产出不同的计划——稳定性对可复现性和调试很重要。
     */
    private final List<WorkerAgent> workers;

    public MultiAgentOrchestrator(MultiAgentPlanner multiAgentPlanner,
                                  AgentAnswerSynthesizer answerSynthesizer,
                                  List<WorkerAgent> workers) {
        this.multiAgentPlanner = multiAgentPlanner;
        this.answerSynthesizer = answerSynthesizer;
        // 按名称排序保证 Worker 列表的顺序稳定，让 Planner 每次看到同样的 Worker 顺序
        this.workers = workers.stream()
                .sorted(Comparator.comparing(WorkerAgent::name))
                .toList();
        this.workerMap = indexWorkers(this.workers);
    }

    /**
     * 执行多 Agent 编排的完整流程：规划 → 调度执行 → 结果合成。
     *
     * <h3>流程分四步</h3>
     * <ol>
     *     <li><b>规划（Plan）</b>：由 Planner 根据用户请求和可用 Worker 清单，产出一个有依赖关系的 Worker 调用计划。</li>
     *     <li><b>调度执行（Execute）</b>：Orchestrator 按依赖拓扑顺序并发执行 Worker，收集每个 Worker 的执行轨迹。</li>
     *     <li><b>轨迹转储（Trace）</b>：将计划与实际执行结果合并为统一的可视化追踪结构。</li>
     *     <li><b>合成（Synthesize）</b>：由 Synthesizer 综合各 Worker 的执行轨迹，产出面向用户的最终答案。</li>
     * </ol>
     *
     * @param conversationContext 会话上下文（含历史对话和长期记忆摘要），供 Planner 和 Synthesizer 参考
     * @param userMessage         用户的当前请求
     * @return 包含最终答案、执行追踪和 Worker 轨迹的完整结果
     */
    public AgentRunResult run(String conversationContext, String userMessage) {
        WorkerPlan workerPlan = multiAgentPlanner.plan(conversationContext, userMessage, workers);
        List<AgentWorkerTrace> workerTraces = executeWorkerPlan(workerPlan, conversationContext, userMessage);
        AgentPlanTrace planTrace = toPlanTrace(workerPlan, workerTraces);
        String finalAnswer = answerSynthesizer.synthesizeMultiAgentResult(
                conversationContext,
                userMessage,
                workerTraces
        );
        return AgentRunResult.multiAgent(finalAnswer, resolveStopReason(workerTraces), toAgentSteps(workerTraces),
                planTrace, workerTraces);
    }

    /**
     * 构建 Worker 名称索引，O(1) 按名查找 Worker 实例。
     * WorkerCall 中引用的是 Worker 名称字符串而非实例引用，所以执行期需要这个索引快速定位。
     *
     * @param workers Worker 列表
     * @return name → Worker 实例的不可变映射
     */
    private Map<String, WorkerAgent> indexWorkers(List<WorkerAgent> workers) {
        Map<String, WorkerAgent> map = new HashMap<>();
        for (WorkerAgent worker : workers) {
            map.put(worker.name(), worker);
        }
        return map;
    }

    /**
     * 按依赖拓扑顺序并发调度执行 Worker 计划，返回所有 Worker 的执行轨迹。
     *
     * <h3>核心算法 —— 依赖驱动的轮次并发</h3>
     * 并非一次性把所有 WorkerCall 丢进线程池，而是 <b>分轮执行</b>：
     * <ol>
     *     <li><b>跳依赖失败</b>：检查 pendingCalls 中有没有依赖了”已失败 Worker”的调用，有则直接跳过。</li>
     *     <li><b>找就绪集</b>：筛选出”所有依赖都已 SUCCESS”的 WorkerCall，它们可以安全并发执行。</li>
     *     <li><b>就绪集为空时</b>：
     *         <ul>
     *             <li>如果本轮有新的跳过项 → 说明依赖正在传播失败，下一轮可能又有新的就绪/跳过项 → continue。</li>
     *             <li>如果本轮没有跳过也没有就绪 → 存在无法解析的依赖（循环引用或引用不存在的 ID）→ 全部标记跳过以避免死循环。</li>
     *         </ul>
     *     </li>
     *     <li><b>并发执行</b>：就绪集内的 Worker 通过 {@link CompletableFuture#supplyAsync} 提交到固定大小线程池，
     *         每个 Worker 独立执行；若执行过程抛出异常，{@code exceptionally} 回调将其转为 FAILED 轨迹，
     *         保证单个 Worker 崩溃不会中断整个调度。</li>
     *     <li><b>收集结果</b>：{@link CompletableFuture#join()} 等待本轮所有就绪 Worker 完成后，
     *         将轨迹写入 traceById，使下一轮能找到它们的完成状态（SUCCESS 或 FAILED）。</li>
     * </ol>
     *
     * <h3>为什么用 {@code join()} 而非 {@code get()}</h3>
     * {@code join()} 抛出的是未经检查的 {@link CompletionException}。如果某个 Worker 通过
     * {@code exceptionally} 已转为 FAILED 轨迹，则 {@code join()} 不会抛异常；若 exceptionally
     * 本身也抛了（极端情况），这个 CompletionException 不会被捕获——意味着调度器遇到不可恢复错误
     * 时应当整体失败，而不是吞掉异常后继续。这是有意设计的 fail-fast 兜底。
     *
     * <h3>并发度控制</h3>
     * 使用 {@code newFixedThreadPool(maxParallelism)} 限制同时执行的 Worker 数量上限，
     * 原因有二：
     * <ol>
     *     <li>每个 Worker 内部会调用 LLM API，同时发送过多请求可能触发限流（HTTP 429）或导致
     *     Token 并发消耗暴涨。</li>
     *     <li>固定线程池限制了真实并发度——即使一轮有 10 个就绪 Worker，也最多只有 4 个同时执行，
     *     其余排队等待，保证下游 LLM 服务稳定。</li>
     * </ol>
     *
     * <h3>失败传播保证</h3>
     * 一个 Worker 失败（FAILED）后，依赖它的 WorkerCall 在下轮循环的
     * {@link #skipCallsWithFailedDependencies} 中会被跳过（SKIPPED），
     * 而 SKIPPED 不等于 SUCCESS，所以不会被 {@link #findReadyCalls} 选入就绪集——
     * 这种传递性保证了失败不会”污染”后续依赖链。
     *
     * @param workerPlan          Planner 产出的 Worker 调用计划
     * @param conversationContext 会话上下文，透传给每个 Worker 作为参考
     * @param userMessage         用户请求，透传给每个 Worker
     * @return 按 callId 升序排列的所有 Worker 执行轨迹
     */
    private List<AgentWorkerTrace> executeWorkerPlan(WorkerPlan workerPlan, String conversationContext, String userMessage) {
        List<AgentWorkerTrace> traces = new ArrayList<>();
        if (workerPlan == null || workerPlan.calls().isEmpty()) {
            return traces;
        }

        // traceById 是算法核心状态：key=callId, value=该 WorkerCall 的执行轨迹。
        // 每轮循环后更新，用于下轮的依赖解析和就绪判断。
        Map<Integer, AgentWorkerTrace> traceById = new HashMap<>();
        List<WorkerCall> pendingCalls = collectExecutableCalls(workerPlan.calls(), traces, traceById);
        // knownCallIds 用于校验依赖引用的 ID 是否合法——引用不存在的 ID 视为依赖失败
        Set<Integer> knownCallIds = collectKnownCallIds(pendingCalls);
        int maxParallelism = resolveMaxParallelism(workerPlan);

        // 固定大小线程池：限制真实并发 Worker 数，保护下游 LLM 服务不被过量请求冲击。
        // try-with-resources 保证线程池在调度结束后正确关闭。
        try (ExecutorService executorService = Executors.newFixedThreadPool(maxParallelism)) {
            while (!pendingCalls.isEmpty()) {
                // 步骤1：标记”前置依赖失败”的 WorkerCall 为 SKIPPED
                int skippedCount = skipCallsWithFailedDependencies(pendingCalls, knownCallIds, traces, traceById);
                // 步骤2：找出所有依赖都已 SUCCESS 的就绪 WorkerCall
                List<WorkerCall> readyCalls = findReadyCalls(pendingCalls, traceById);
                if (readyCalls.isEmpty()) {
                    if (skippedCount == 0) {
                        // 既无就绪也无跳过 → 存在无法解析的依赖（循环引用或引用缺失 ID）
                        skipUnresolvableCalls(pendingCalls, traces, traceById);
                    }
                    // 有跳过时继续循环：被跳过的调用可能解锁其他调用进入就绪
                    continue;
                }
                // 步骤3：将就绪调用从待处理集中移除（避免重复执行）
                pendingCalls.removeAll(readyCalls);
                // 步骤4：并发提交所有就绪 WorkerCall，每个都用 exceptionally 保护防止崩溃
                List<CompletableFuture<AgentWorkerTrace>> futures = readyCalls.stream()
                        .map(call -> CompletableFuture.supplyAsync(
                                        () -> executeReadyCall(call, conversationContext, userMessage),
                                        executorService
                                )
                                .exceptionally(exception -> failedTrace(call, rootMessage(exception))))
                        .toList();
                // 步骤5：等待本轮所有就绪 Worker 完成，收集轨迹
                for (CompletableFuture<AgentWorkerTrace> future : futures) {
                    AgentWorkerTrace trace = future.join();
                    traces.add(trace);
                    traceById.put(trace.callId(), trace);
                }
            }
        }
        // 按 callId 升序排列输出，保证每次运行相同计划的轨迹顺序稳定
        return traces.stream()
                .sorted(Comparator.comparingInt(AgentWorkerTrace::callId))
                .toList();
    }

    /**
     * 从计划中收集可执行的 WorkerCall，同时去重和上报重复 ID 的调用。
     *
     * <h3>为什么需要去重</h3>
     * Planner 是 LLM 驱动的，理论上可能产出 callId 重复的计划（虽然概率低）。
     * 重复 ID 会导致 traceById 覆盖、依赖解析混乱，因此必须在调度前拦截。
     * 首次出现的调用保留，重复的后续调用直接标记 SKIPPED 并记录原因。
     *
     * @param calls      Planner 产出的所有 WorkerCall
     * @param traces     已收集的轨迹列表（原地追加重复 ID 的跳过轨迹）
     * @param traceById  已知的轨迹索引（原地写入重复 ID 的跳过轨迹）
     * @return 去重后的待执行 WorkerCall 列表
     */
    private List<WorkerCall> collectExecutableCalls(List<WorkerCall> calls, List<AgentWorkerTrace> traces,
                                                    Map<Integer, AgentWorkerTrace> traceById) {
        List<WorkerCall> pendingCalls = new ArrayList<>();
        Set<Integer> seenIds = new HashSet<>();
        for (WorkerCall call : calls) {
            if (!seenIds.add(call.id())) {
                // callId 重复的调用立即标记跳过，不进入待执行列表
                AgentWorkerTrace trace = skippedTrace(call, "Worker 调用 ID 重复，已跳过重复项。");
                traces.add(trace);
                continue;
            }
            pendingCalls.add(call);
        }
        return pendingCalls;
    }

    /**
     * 收集所有合法的 WorkerCall ID，用于后续校验依赖引用是否存在。
     * 如果某个 WorkerCall 的 dependsOn 引用了不在这个集合里的 ID，
     * 说明 Planner 产出了无效依赖→该 WorkerCall 会被跳过（见 {@link #dependencyFailureReason}）。
     *
     * @param calls 去重后的待执行 WorkerCall 列表
     * @return 所有合法的 callId 集合
     */
    private Set<Integer> collectKnownCallIds(List<WorkerCall> calls) {
        Set<Integer> ids = new HashSet<>();
        for (WorkerCall call : calls) {
            ids.add(call.id());
        }
        return ids;
    }

    /**
     * 解析本次调度的最大并行 Workers 数。
     *
     * <h3>取值逻辑</h3>
     * 取 min(默认上限4, 计划中的Worker数)，且不小于1。
     * 例如：计划只有2个Worker → 并行度=2（线程不会空闲浪费）；计划有10个Worker → 并行度=4（受上限约束）。
     *
     * @param workerPlan 当前调度计划
     * @return 实际最大并行数，保证 >= 1
     */
    private int resolveMaxParallelism(WorkerPlan workerPlan) {
        int callCount = workerPlan == null ? 0 : workerPlan.calls().size();
        return Math.max(1, Math.min(DEFAULT_MAX_PARALLEL_WORKERS, Math.max(1, callCount)));
    }

    /**
     * 扫描待执行列表，将"前置依赖已经失败"的 WorkerCall 标记为 SKIPPED。
     *
     * <h3>为什么要在每轮开始时扫描</h3>
     * 上一轮执行结束时可能有 Worker 失败（FAILED），而依赖它的 WorkerCall 还留在 pendingCalls 中。
     * 本方法在下轮"找就绪集"之前先把这些"依赖已不可能满足"的调用清理掉，避免它们被错误放行。
     * 同时，写入 traceById 可以让更深层级的依赖（依赖-依赖）也被传播跳过。
     *
     * @param pendingCalls  当前待执行的 WorkerCall 列表（会被原地修改，移除已跳过的项）
     * @param knownCallIds  所有合法的 callId（用于检测依赖引用不存在的 ID）
     * @param traces        已收集的轨迹列表（原地追加跳过轨迹）
     * @param traceById     已知的轨迹索引（原地写入跳过轨迹，使传播生效）
     * @return 本轮跳过的 WorkerCall 数量（用于判断是否还有进展可能）
     */
    private int skipCallsWithFailedDependencies(List<WorkerCall> pendingCalls, Set<Integer> knownCallIds,
                                                List<AgentWorkerTrace> traces,
                                                Map<Integer, AgentWorkerTrace> traceById) {
        List<WorkerCall> skippedCalls = pendingCalls.stream()
                .filter(call -> dependencyFailureReason(call, knownCallIds, traceById) != null)
                .toList();
        for (WorkerCall call : skippedCalls) {
            AgentWorkerTrace trace = skippedTrace(call, dependencyFailureReason(call, knownCallIds, traceById));
            traces.add(trace);
            traceById.put(trace.callId(), trace);
        }
        pendingCalls.removeAll(skippedCalls);
        return skippedCalls.size();
    }

    /**
     * 判断一个 WorkerCall 是否有失败的前置依赖，若有则返回失败原因，否则返回 null。
     *
     * <h3>两个检查维度</h3>
     * <ol>
     *     <li><b>依赖 ID 不存在</b>：如果 dependsOn 引用了不在 knownCallIds 中的 ID，
     *     说明 Planner 产出了指向不存在 Worker 的依赖，该调用无法执行。</li>
     *     <li><b>前置未成功</b>：如果依赖的 Worker 已执行但状态不是 SUCCESS（可能是 FAILED 或 SKIPPED），
     *     则该调用也无法正确执行。注意这里的判断条件是 {@code status() != WorkerStatus.SUCCESS}
     *     而不是 {@code status() == WorkerStatus.FAILED}——SKIPPED 的前置也不应被依赖。</li>
     * </ol>
     *
     * @param call         待检查的 WorkerCall
     * @param knownCallIds 所有合法的 callId（用于判断 ID 是否存在）
     * @param traceById    已完成的 Worker 轨迹索引（用于检查完成状态）
     * @return 失败原因字符串，所有依赖正常则返回 null
     */
    private String dependencyFailureReason(WorkerCall call, Set<Integer> knownCallIds,
                                           Map<Integer, AgentWorkerTrace> traceById) {
        for (Integer dependencyId : call.dependsOn()) {
            if (!knownCallIds.contains(dependencyId)) {
                return "依赖的 Worker 调用不存在：" + dependencyId;
            }
            AgentWorkerTrace dependencyTrace = traceById.get(dependencyId);
            if (dependencyTrace != null && dependencyTrace.status() != WorkerStatus.SUCCESS) {
                return "前置 Worker 未成功，已跳过本次调用。";
            }
        }
        return null;
    }

    /**
     * 从待执行列表中找出"所有前置依赖都已成功完成"的就绪 WorkerCall。
     *
     * <h3>就绪判定逻辑</h3>
     * 一个 WorkerCall 就绪当且仅当它的 <b>所有</b> dependsOn 引用的 Worker 都：
     * <ol>
     *     <li>已经存在于 traceById（说明已经执行完毕）</li>
     *     <li>状态是 SUCCESS（不是 FAILED 也不是 SKIPPED）</li>
     * </ol>
     * 没有 dependsOn 的 WorkerCall 只要出现在 pendingCalls 中就立即就绪（allMatch 对空集合返回 true）。
     *
     * <h3>为什么需要 traceById 而非其他地方查询</h3>
     * traceById 是调度循环的状态中心——每轮执行完就绪 Worker 后立即写入。
     * 下一轮通过 traceById 就能知道哪些依赖已完成，不需要额外的信号量或锁。
     *
     * @param pendingCalls 当前待执行的 WorkerCall 列表（已扣除失败依赖跳过项）
     * @param traceById    已完成的 Worker 轨迹索引
     * @return 就绪的 WorkerCall 列表（可能为空）
     */
    private List<WorkerCall> findReadyCalls(List<WorkerCall> pendingCalls, Map<Integer, AgentWorkerTrace> traceById) {
        return pendingCalls.stream()
                .filter(call -> call.dependsOn().stream()
                        .allMatch(dependencyId -> {
                            AgentWorkerTrace dependencyTrace = traceById.get(dependencyId);
                            return dependencyTrace != null && dependencyTrace.status() == WorkerStatus.SUCCESS;
                        }))
                .toList();
    }

    /**
     * 将无法解析的 WorkerCall 全部标记为 SKIPPED——兜底终止调度循环。
     *
     * <h3>触发条件</h3>
     * 当前轮没有就绪调用，也没有新跳过任何依赖失败的调用（说明不存在可传播的失败），
     * 但 pendingCalls 仍不为空。这通常意味着 Planner 产出了循环依赖。
     *
     * 例如：WorkerCall #2 dependsOn [3]，WorkerCall #3 dependsOn [2]。
     * 两个都互相等待对方先完成，永远无法就绪→死锁→全部跳过。
     *
     * <h3>为什么不抛出异常</h3>
     * 这是 Planner（LLM）的错误，不是运行时错误。将问题暴露在追踪中让用户和 Synthesizer
     * 看到"部分结果"比直接报 500 更友好——Synthesizer 可以基于成功的 Worker 结果尽力产出答案。
     *
     * @param pendingCalls 剩余的无法解析调用（会被清空）
     * @param traces       已收集的轨迹列表（原地追加跳过轨迹）
     * @param traceById    已知的轨迹索引（原地写入）
     */
    private void skipUnresolvableCalls(List<WorkerCall> pendingCalls, List<AgentWorkerTrace> traces,
                                       Map<Integer, AgentWorkerTrace> traceById) {
        for (WorkerCall call : pendingCalls) {
            AgentWorkerTrace trace = skippedTrace(call, "Worker 依赖关系形成循环或无法满足，已跳过。");
            traces.add(trace);
            traceById.put(trace.callId(), trace);
        }
        pendingCalls.clear();
    }

    /**
     * 执行一个就绪的 WorkerCall：查找到对应的 Worker 实例并调用其 execute。
     *
     * <h3>Worker 查找失败的处理</h3>
     * 如果 workerName 在已注册的 Worker 中找不到（Planner 产出了不存在的 Worker 名称），
     * 不走 Worker.execute 路径，直接返回 SKIPPED 轨迹——这在失败传播上和 FAILED 等价，
     * 会让依赖它的 WorkerCall 也被跳过。
     *
     * @param call                就绪的 WorkerCall（已经过依赖校验）
     * @param conversationContext 会话上下文
     * @param userMessage         用户请求
     * @return Worker 的执行轨迹（成功、失败或跳过）
     */
    private AgentWorkerTrace executeReadyCall(WorkerCall call, String conversationContext, String userMessage) {
        WorkerAgent worker = workerMap.get(call.workerName());
        if (worker == null) {
            return skippedTrace(call, "未知 Worker：" + call.workerName());
        }
        return worker.execute(call, conversationContext, userMessage);
    }

    /**
     * 构造一个 SKIPPED 状态的 Worker 执行轨迹——表示该 Worker 因调度层面的原因被跳过，
     * 未真正走到 Worker 内部的业务执行。
     *
     * <h3>SKIPPED 与 FAILED 的区别</h3>
     * <ul>
     *     <li><b>SKIPPED</b>：Orchestrator 层面的决定——前置依赖失败、ID 重复、循环依赖等，
     *     不会消耗 LLM 调用。</li>
     *     <li><b>FAILED</b>：Worker 被实际调用了但在执行期抛出了异常。</li>
     * </ul>
     * 二者在"对下游依赖的影响"上等价：都不会让依赖它的 WorkerCall 就绪。
     *
     * @param call   被跳过的 WorkerCall
     * @param reason 跳过的原因（中文描述，会出现在 trace 的 errorMessage 和 brief 中）
     * @return SKIPPED 状态的执行轨迹
     */
    private AgentWorkerTrace skippedTrace(WorkerCall call, String reason) {
        return new AgentWorkerTrace(
                call.id(),
                TextUtil.trimToDefault(call.workerName(), "unknown_worker"),
                "未执行",
                "未匹配到可执行 Worker",
                WorkerStatus.SKIPPED,
                call.subTask(),
                call.sharedContext(),
                null,
                WorkerBrief.fromSummary(reason, List.of(), WorkerStatus.SKIPPED),
                List.of(),
                reason,
                null,
                List.of()
        );
    }

    /**
     * 将 Worker 调度计划与实际执行轨迹合并，产出统一的可视化追踪结构。
     *
     * <h3>plannedSteps vs executions 的区别</h3>
     * <ul>
     *     <li><b>plannedSteps</b>：Planner 产出的原始计划步骤——"计划做什么"。</li>
     *     <li><b>executions</b>：Workers 实际执行后的轨迹——"实际发生了什么"，含状态和摘要。</li>
     * </ul>
     * 二者对齐后交给前端做可视化对比（计划 vs 实际），帮助用户理解多 Agent 的调度结果。
     *
     * @param workerPlan   Planner 产出的原始调度计划
     * @param workerTraces Workers 执行后的轨迹列表
     * @return 合并后的计划追踪，workerPlan 为 null 时返回 null
     */
    private AgentPlanTrace toPlanTrace(WorkerPlan workerPlan, List<AgentWorkerTrace> workerTraces) {
        if (workerPlan == null) {
            return null;
        }
        List<AgentPlanStep> plannedSteps = workerPlan.calls().stream()
                .map(call -> new AgentPlanStep(
                        call.id(),
                        call.subTask(),
                        call.workerName(),
                        call.sharedContext(),
                        call.dependsOn(),
                        "Worker 产出可供最终合成的子任务结论"
                ))
                .toList();
        List<PlanStepExecution> executions = workerTraces.stream()
                .map(trace -> new PlanStepExecution(
                        trace.callId(),
                        trace.subTask(),
                        trace.workerName(),
                        trace.sharedContext(),
                        List.of(),
                        "Worker 产出可供最终合成的子任务结论",
                        toPlanStatus(trace.status()),
                        trace.summary(),
                        trace.errorMessage()
                ))
                .toList();
        return new AgentPlanTrace(
                workerPlan.objective(),
                workerPlan.rationale(),
                workerPlan.coverageCheck(),
                plannedSteps,
                executions
        );
    }

    /**
     * 将 Worker 执行状态映射为通用的计划步骤状态。
     * 两个状态枚举的取值是对应的，但属于不同抽象层次：
     * WorkerStatus 属于多 Agent 调度层，PlanStepStatus 属于统一追踪层。
     *
     * @param status Worker 执行状态
     * @return 对应的计划步骤状态
     */
    private PlanStepStatus toPlanStatus(WorkerStatus status) {
        if (status == WorkerStatus.SUCCESS) {
            return PlanStepStatus.SUCCESS;
        }
        if (status == WorkerStatus.SKIPPED) {
            return PlanStepStatus.SKIPPED;
        }
        return PlanStepStatus.FAILED;
    }

    /**
     * 构造一个 FAILED 状态的 Worker 执行轨迹——表示 Worker 在执行期间抛出了未捕获异常。
     *
     * <h3>调用时机</h3>
     * 由 {@link CompletableFuture#exceptionally} 回调调用，当 {@link #executeReadyCall} 或
     * Worker 内部的 {@code execute()} 方法抛出任何异常时触发。exceptionally 将异常"消化"为
     * 一个带有错误信息的正常轨迹对象，让协同并发调度的 join() 不会收到异常。
     *
     * <h3>对依赖传播的影响</h3>
     * FAILED 轨迹与 SKIPPED 轨迹在 traceById 中的区别：二者都不是 SUCCESS，
     * 所以依赖它们的 WorkerCall 在下一轮会被 {@link #skipCallsWithFailedDependencies} 跳过。
     *
     * @param call         执行失败的 WorkerCall
     * @param errorMessage 从异常中提取的可读错误信息
     * @return FAILED 状态的执行轨迹
     */
    private AgentWorkerTrace failedTrace(WorkerCall call, String errorMessage) {
        return new AgentWorkerTrace(
                call.id(),
                TextUtil.trimToDefault(call.workerName(), "unknown_worker"),
                "执行异常",
                "Worker 执行时出现未捕获异常",
                WorkerStatus.FAILED,
                call.subTask(),
                call.sharedContext(),
                null,
                WorkerBrief.fromSummary(errorMessage, List.of(), WorkerStatus.FAILED),
                List.of(),
                TextUtil.trimToDefault(errorMessage, "Worker 执行异常"),
                null,
                List.of()
        );
    }

    /**
     * 从异常中提取根因的可读错误信息。
     *
     * <h3>为什么需要解包 CompletionException</h3>
     * {@link CompletableFuture#exceptionally} 接收到的异常类型有两种可能：
     * <ol>
     *     <li>Worker 内部的 RuntimeException → 被包装在 {@link CompletionException} 中（getCause 是原始异常）。</li>
     *     <li>Worker 内部的 Error 或 checked exception → 也被包装在 CompletionException 中。</li>
     * </ol>
     * 需要解包一层取根因，否则错误信息是"java.util.concurrent.CompletionException: ..."，
     * 对用户和调试都不可读。
     *
     * @param throwable exceptionally 回调接收到的异常
     * @return 从根因中提取的可读错误信息，极端情况下为类名
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        if (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? "Worker 执行异常" : TextUtil.trimToDefault(current.getMessage(), current.getClass().getSimpleName());
    }


    /**
     * 将 Worker 执行轨迹转换为统一的 ReAct 风格步骤列表，供前端主流程展示。
     *
     * <h3>为什么需要这个转换</h3>
     * 前端主流程（/agent/chat SSE）展示步骤时使用统一的 {@link AgentStep} 结构。
     * 多 Agent 模式下虽然没有 ReAct 的 Thought/Action/Observation 循环，
     * 但每个 Worker 的调度本身就是一步——通过本方法将其"伪装"成步骤便于前端统一渲染。
     *
     * @param workerTraces 所有 Worker 的执行轨迹
     * @return 从1开始编号的 ReAct 风格步骤列表
     */
    private List<AgentStep> toAgentSteps(List<AgentWorkerTrace> workerTraces) {
        List<AgentStep> steps = new ArrayList<>();
        int index = 1;
        for (AgentWorkerTrace trace : workerTraces) {
            steps.add(new AgentStep(
                    index++,
                    "Worker 调度：" + trace.role() + " 处理「" + trace.subTask() + "」",
                    trace.workerName(),
                    trace.sharedContext(),
                    TextUtil.trimToDefault(trace.summary(), trace.errorMessage())
            ));
        }
        return steps;
    }

    /**
     * 根据 Worker 执行轨迹解析停止原因——当有 Worker 失败或跳过时给出可读汇总。
     *
     * <h3>返回值约定</h3>
     * <ul>
     *     <li>全部 SUCCESS → 返回 null（表示正常完成，无需额外说明）</li>
     *     <li>有 FAILED 或 SKIPPED → 返回形如"失败 N 个，跳过 M 个"的汇总信息</li>
     * </ul>
     * 这个 stopReason 会出现在最终响应中，让用户和前端知道本次执行是否完全成功。
     *
     * @param traces 所有 Worker 的执行轨迹
     * @return 停止原因字符串，全部成功时返回 null
     */
    private String resolveStopReason(List<AgentWorkerTrace> traces) {
        long failed = traces.stream().filter(trace -> trace.status() == WorkerStatus.FAILED).count();
        long skipped = traces.stream().filter(trace -> trace.status() == WorkerStatus.SKIPPED).count();
        if (failed == 0 && skipped == 0) {
            return null;
        }
        return "多 Agent 执行未完全成功：失败 " + failed + " 个，跳过 " + skipped + " 个。";
    }
}
