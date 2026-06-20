package com.link.linkagent.core.multi;

import java.util.List;

/**
 * 多 Agent Orchestrator 输出的 Worker 调度计划。
 */
public record WorkerPlan(
        String objective,
        List<WorkerCall> calls,
        String rationale,
        String coverageCheck
) {

    public WorkerPlan {
        calls = calls == null ? List.of() : List.copyOf(calls);
    }
}
