package com.link.linkagent.core.multi;

import com.link.linkagent.core.AgentStep;
import com.link.linkagent.core.plan.AgentPlanTrace;

import java.util.List;

/**
 * 多 Agent 的 Worker 执行轨迹。
 * <p>
 * 一个 Worker 可以是内部再跑 PaE 的复杂 Agent，也可以是直接 LLM 推理 Agent，因此同时保留 planTrace 和 steps。
 */
public record AgentWorkerTrace(
        int callId,
        String workerName,
        String role,
        String capability,
        WorkerStatus status,
        String subTask,
        String sharedContext,
        String summary,
        String errorMessage,
        AgentPlanTrace planTrace,
        List<AgentStep> steps
) {

    public AgentWorkerTrace {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
