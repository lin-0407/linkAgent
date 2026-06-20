package com.link.linkagent.core.multi;

/**
 * 多 Agent Worker 契约。
 * <p>
 * Worker 是完整 Agent 能力单元，而不是 Tool 的别名。Orchestrator 只知道 name/capability，
 * 新增 Worker 时无需修改 Orchestrator 代码。
 */
public interface WorkerAgent {

    String name();

    String role();

    String capability();

    AgentWorkerTrace execute(WorkerCall call, String conversationContext, String userMessage);
}
