package com.link.linkagent.core.multi;

import java.util.List;

/**
 * 多 Agent 编排中的一次 Worker 调用。
 *
 * @param id            调用 ID，从 1 开始，供依赖关系引用
 * @param workerName    Worker 名称，必须来自 Orchestrator 注入的能力清单
 * @param subTask       分配给 Worker 的子任务
 * @param sharedContext 需要随子任务传递的共享上下文摘要
 * @param dependsOn     依赖的 Worker 调用 ID
 */
public record WorkerCall(
        int id,
        String workerName,
        String subTask,
        String sharedContext,
        List<Integer> dependsOn
) {

    public WorkerCall {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
