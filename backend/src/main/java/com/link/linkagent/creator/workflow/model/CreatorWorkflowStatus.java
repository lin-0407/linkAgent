package com.link.linkagent.creator.workflow.model;

/**
 * 工作流会话状态。
 * 状态先保持简单，后续接 SSE、确认机制和失败回放时可以继续沿用这些稳定节点。
 */
public enum CreatorWorkflowStatus {
    /**
     * 已创建：会话记录已经生成，但还没有开始装载上下文。
     */
    CREATED,

    /**
     * 正在装载任务材料：后端正在把标题、简介、文稿、字幕转换成消息流。
     */
    CONTEXT_LOADING,

    /**
     * 等待用户补充输入：上下文已准备好，用户可以继续补充要求或触发分析。
     */
    WAITING_USER_INPUT,

    /**
     * Agent 正在分析：后续接入 LLM 或工具调用时使用，表示本轮工作流正在执行。
     */
    RUNNING,

    /**
     * 等待用户确认结果：Agent 已生成建议，但系统不能自动推进，需要用户明确采用。
     */
    WAITING_CONFIRMATION,

    /**
     * 用户已确认：本轮建议或结果已经被用户采用。
     */
    CONFIRMED,

    /**
     * 执行失败：LLM、工具、解析或保存过程失败，需要保留失败原因便于重试。
     */
    FAILED,

    /**
     * 用户已取消：用户主动停止本轮工作流，后续不应继续写入分析消息。
     */
    CANCELLED
}
