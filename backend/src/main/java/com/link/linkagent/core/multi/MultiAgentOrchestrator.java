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
 * 多 Agent Orchestrator。
 * <p>
 * Orchestrator 只负责任务拆分、Worker 调度和结果汇总，不直接调用工具或处理业务细节。
 * 这让新增 Worker 时只需要新增 Bean，不需要改调度器主体。
 */
@Component
public class MultiAgentOrchestrator {

    private static final int DEFAULT_MAX_PARALLEL_WORKERS = 4;

    private final MultiAgentPlanner multiAgentPlanner;
    private final AgentAnswerSynthesizer answerSynthesizer;
    private final Map<String, WorkerAgent> workerMap;
    private final List<WorkerAgent> workers;

    public MultiAgentOrchestrator(MultiAgentPlanner multiAgentPlanner,
                                  AgentAnswerSynthesizer answerSynthesizer,
                                  List<WorkerAgent> workers) {
        this.multiAgentPlanner = multiAgentPlanner;
        this.answerSynthesizer = answerSynthesizer;
        this.workers = workers.stream()
                .sorted(Comparator.comparing(WorkerAgent::name))
                .toList();
        this.workerMap = indexWorkers(this.workers);
    }

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

    private Map<String, WorkerAgent> indexWorkers(List<WorkerAgent> workers) {
        Map<String, WorkerAgent> map = new HashMap<>();
        for (WorkerAgent worker : workers) {
            map.put(worker.name(), worker);
        }
        return map;
    }

    private List<AgentWorkerTrace> executeWorkerPlan(WorkerPlan workerPlan, String conversationContext, String userMessage) {
        List<AgentWorkerTrace> traces = new ArrayList<>();
        if (workerPlan == null || workerPlan.calls().isEmpty()) {
            return traces;
        }

        Map<Integer, AgentWorkerTrace> traceById = new HashMap<>();
        List<WorkerCall> pendingCalls = collectExecutableCalls(workerPlan.calls(), traces, traceById);
        Set<Integer> knownCallIds = collectKnownCallIds(pendingCalls);
        int maxParallelism = resolveMaxParallelism(workerPlan);

        // 每一轮只并发执行“依赖已经全部成功”的 Worker，避免后置 Worker 读到未完成或失败的前置结果。
        try (ExecutorService executorService = Executors.newFixedThreadPool(maxParallelism)) {
            while (!pendingCalls.isEmpty()) {
                int skippedCount = skipCallsWithFailedDependencies(pendingCalls, knownCallIds, traces, traceById);
                List<WorkerCall> readyCalls = findReadyCalls(pendingCalls, traceById);
                if (readyCalls.isEmpty()) {
                    if (skippedCount == 0) {
                        skipUnresolvableCalls(pendingCalls, traces, traceById);
                    }
                    continue;
                }
                pendingCalls.removeAll(readyCalls);
                List<CompletableFuture<AgentWorkerTrace>> futures = readyCalls.stream()
                        .map(call -> CompletableFuture.supplyAsync(
                                        () -> executeReadyCall(call, conversationContext, userMessage),
                                        executorService
                                )
                                .exceptionally(exception -> failedTrace(call, rootMessage(exception))))
                        .toList();
                for (CompletableFuture<AgentWorkerTrace> future : futures) {
                    AgentWorkerTrace trace = future.join();
                    traces.add(trace);
                    traceById.put(trace.callId(), trace);
                }
            }
        }
        return traces.stream()
                .sorted(Comparator.comparingInt(AgentWorkerTrace::callId))
                .toList();
    }

    private List<WorkerCall> collectExecutableCalls(List<WorkerCall> calls, List<AgentWorkerTrace> traces,
                                                    Map<Integer, AgentWorkerTrace> traceById) {
        List<WorkerCall> pendingCalls = new ArrayList<>();
        Set<Integer> seenIds = new HashSet<>();
        for (WorkerCall call : calls) {
            if (!seenIds.add(call.id())) {
                AgentWorkerTrace trace = skippedTrace(call, "Worker 调用 ID 重复，已跳过重复项。");
                traces.add(trace);
                continue;
            }
            pendingCalls.add(call);
        }
        return pendingCalls;
    }

    private Set<Integer> collectKnownCallIds(List<WorkerCall> calls) {
        Set<Integer> ids = new HashSet<>();
        for (WorkerCall call : calls) {
            ids.add(call.id());
        }
        return ids;
    }

    private int resolveMaxParallelism(WorkerPlan workerPlan) {
        int callCount = workerPlan == null ? 0 : workerPlan.calls().size();
        return Math.max(1, Math.min(DEFAULT_MAX_PARALLEL_WORKERS, Math.max(1, callCount)));
    }

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

    private List<WorkerCall> findReadyCalls(List<WorkerCall> pendingCalls, Map<Integer, AgentWorkerTrace> traceById) {
        return pendingCalls.stream()
                .filter(call -> call.dependsOn().stream()
                        .allMatch(dependencyId -> {
                            AgentWorkerTrace dependencyTrace = traceById.get(dependencyId);
                            return dependencyTrace != null && dependencyTrace.status() == WorkerStatus.SUCCESS;
                        }))
                .toList();
    }

    private void skipUnresolvableCalls(List<WorkerCall> pendingCalls, List<AgentWorkerTrace> traces,
                                       Map<Integer, AgentWorkerTrace> traceById) {
        for (WorkerCall call : pendingCalls) {
            AgentWorkerTrace trace = skippedTrace(call, "Worker 依赖关系形成循环或无法满足，已跳过。");
            traces.add(trace);
            traceById.put(trace.callId(), trace);
        }
        pendingCalls.clear();
    }

    private AgentWorkerTrace executeReadyCall(WorkerCall call, String conversationContext, String userMessage) {
        WorkerAgent worker = workerMap.get(call.workerName());
        if (worker == null) {
            return skippedTrace(call, "未知 Worker：" + call.workerName());
        }
        return worker.execute(call, conversationContext, userMessage);
    }

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

    private PlanStepStatus toPlanStatus(WorkerStatus status) {
        if (status == WorkerStatus.SUCCESS) {
            return PlanStepStatus.SUCCESS;
        }
        if (status == WorkerStatus.SKIPPED) {
            return PlanStepStatus.SKIPPED;
        }
        return PlanStepStatus.FAILED;
    }

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

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        if (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? "Worker 执行异常" : TextUtil.trimToDefault(current.getMessage(), current.getClass().getSimpleName());
    }


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

    private String resolveStopReason(List<AgentWorkerTrace> traces) {
        long failed = traces.stream().filter(trace -> trace.status() == WorkerStatus.FAILED).count();
        long skipped = traces.stream().filter(trace -> trace.status() == WorkerStatus.SKIPPED).count();
        if (failed == 0 && skipped == 0) {
            return null;
        }
        return "多 Agent 执行未完全成功：失败 " + failed + " 个，跳过 " + skipped + " 个。";
    }
}
