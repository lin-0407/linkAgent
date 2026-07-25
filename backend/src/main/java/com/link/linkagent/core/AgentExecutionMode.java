package com.link.linkagent.core;

/**
 * 通用 Agent 的执行模式。
 * <p>
 * AUTO 是入口默认模式，由后端按任务复杂度路由；另外三个值用于前端或调用方强制指定路径，
 * 便于演示和排障时直接观察不同内核行为。
 */
public enum AgentExecutionMode {

    AUTO,
    REACT,
    PLAN_EXECUTE,
    MULTI_AGENT;

    public static AgentExecutionMode normalize(AgentExecutionMode mode) {
        return mode == null ? AUTO : mode;
    }

}
