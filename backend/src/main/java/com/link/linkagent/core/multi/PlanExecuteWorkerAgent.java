package com.link.linkagent.core.multi;

import com.link.linkagent.core.AgentRunResult;
import com.link.linkagent.core.citation.AgentEvidence;
import com.link.linkagent.core.plan.PlanAndExecuteAgent;
import com.link.linkagent.core.plan.PlanStepExecution;
import com.link.linkagent.core.plan.PlanStepStatus;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 多 Agent 模式下的工具取证 Worker。
 * <p>
 * Worker 内部复用 PaE Agent，这样复杂子任务仍然能“先规划再执行”，而不是让 Orchestrator 直接越级调用工具。
 */
@Component
public class PlanExecuteWorkerAgent implements WorkerAgent {

    private final PlanAndExecuteAgent planAndExecuteAgent;

    public PlanExecuteWorkerAgent(PlanAndExecuteAgent planAndExecuteAgent) {
        this.planAndExecuteAgent = planAndExecuteAgent;
    }

    @Override
    public String name() {
        return "plan_execute_worker";
    }

    @Override
    public String role() {
        return "工具取证 Agent";
    }

    @Override
    public String capability() {
        return "适合需要调用工具、检索案例、分步骤验证事实或先取证再判断的子任务。";
    }

    @Override
    public AgentWorkerTrace execute(WorkerCall call, String conversationContext, String userMessage) {
        try {
            AgentRunResult result = planAndExecuteAgent.run(
                    buildWorkerContext(conversationContext, call),
                    call.subTask()
            );
            WorkerStatus status = result.stopReason() == null ? WorkerStatus.SUCCESS : WorkerStatus.FAILED;
            List<AgentEvidence> evidences = buildEvidences(call, result);
            WorkerBrief brief = WorkerBrief.fromSummary(result.finalAnswer(), evidenceIds(evidences), status);
            return new AgentWorkerTrace(
                    call.id(),
                    name(),
                    role(),
                    capability(),
                    status,
                    call.subTask(),
                    call.sharedContext(),
                    result.finalAnswer(),
                    brief,
                    evidences,
                    result.stopReason(),
                    result.planTrace(),
                    result.steps()
            );
        } catch (RuntimeException exception) {
            return failedTrace(call, exception.getMessage());
        }
    }

    private String buildWorkerContext(String conversationContext, WorkerCall call) {
        return """
                【父级对话上下文】
                %s

                【Orchestrator 共享上下文】
                %s
                """.formatted(
                TextUtil.trimToDefault(conversationContext, "（无上下文）"),
                TextUtil.trimToDefault(call.sharedContext(), "（无共享上下文）")
        );
    }

    private AgentWorkerTrace failedTrace(WorkerCall call, String errorMessage) {
        return new AgentWorkerTrace(
                call.id(),
                name(),
                role(),
                capability(),
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

    private List<AgentEvidence> buildEvidences(WorkerCall call, AgentRunResult result) {
        List<AgentEvidence> evidences = new ArrayList<>();
        if (result.planTrace() != null) {
            for (PlanStepExecution execution : result.planTrace().executions()) {
                if (execution.status() == PlanStepStatus.SUCCESS && TextUtil.hasText(execution.observation())) {
                    evidences.add(AgentEvidence.fromWorkerPlanStep(
                            call.id(),
                            execution.stepId(),
                            execution.action(),
                            execution.observation()
                    ));
                }
            }
        }
        // 没有工具观察时仍保留 Worker 结论来源，但置信度较低，供 Synthesizer 区分事实和推断。
        if (evidences.isEmpty() && TextUtil.hasText(result.finalAnswer())) {
            evidences.add(AgentEvidence.fromWorkerSummary(call.id(), name(), result.finalAnswer()));
        }
        return evidences;
    }

    private List<String> evidenceIds(List<AgentEvidence> evidences) {
        return evidences.stream()
                .map(AgentEvidence::evidenceId)
                .toList();
    }
}
