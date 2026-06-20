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

/**
 * 多 Agent Orchestrator。
 * <p>
 * Orchestrator 只负责任务拆分、Worker 调度和结果汇总，不直接调用工具或处理业务细节。
 * 这让新增 Worker 时只需要新增 Bean，不需要改调度器主体。
 */
@Component
public class MultiAgentOrchestrator {

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
                formatWorkerTraces(workerTraces)
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
        Set<Integer> successCallIds = new HashSet<>();
        if (workerPlan == null || workerPlan.calls().isEmpty()) {
            return traces;
        }
        for (WorkerCall call : workerPlan.calls()) {
            AgentWorkerTrace trace = executeCall(call, successCallIds, conversationContext, userMessage);
            traces.add(trace);
            if (trace.status() == WorkerStatus.SUCCESS) {
                successCallIds.add(trace.callId());
            }
        }
        return traces;
    }

    private AgentWorkerTrace executeCall(WorkerCall call, Set<Integer> successCallIds,
                                         String conversationContext, String userMessage) {
        if (!successCallIds.containsAll(call.dependsOn())) {
            return skippedTrace(call, "前置 Worker 未成功，已跳过本次调用。");
        }
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

    private String formatWorkerTraces(List<AgentWorkerTrace> traces) {
        if (traces.isEmpty()) {
            return "没有 Worker 执行结果。";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentWorkerTrace trace : traces) {
            builder.append(trace.callId()).append(". ")
                    .append(trace.workerName())
                    .append("｜").append(trace.status())
                    .append("｜子任务：").append(trace.subTask())
                    .append("｜结论：").append(TextUtil.preview(trace.summary(), 900, "无"))
                    .append("｜错误：").append(TextUtil.trimToDefault(trace.errorMessage(), "无"))
                    .append("\n");
        }
        return builder.toString();
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
