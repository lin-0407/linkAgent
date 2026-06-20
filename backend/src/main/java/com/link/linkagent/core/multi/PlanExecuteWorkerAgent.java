package com.link.linkagent.core.multi;

import com.link.linkagent.core.AgentRunResult;
import com.link.linkagent.core.plan.PlanAndExecuteAgent;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Component;

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
            return new AgentWorkerTrace(
                    call.id(),
                    name(),
                    role(),
                    capability(),
                    result.stopReason() == null ? WorkerStatus.SUCCESS : WorkerStatus.FAILED,
                    call.subTask(),
                    call.sharedContext(),
                    result.finalAnswer(),
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
                TextUtil.trimToDefault(errorMessage, "Worker 执行异常"),
                null,
                java.util.List.of()
        );
    }
}
