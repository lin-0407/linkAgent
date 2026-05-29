package com.link.linkagent.creator.workflow.model;

/**
 * 工作流步骤类型。
 * 第一版只记录创作者发布前优化链路的关键业务节点，避免直接复用通用 Agent trace 造成理解成本过高。
 */
public enum CreatorWorkflowStepType {
    /**
     * 装载任务上下文，例如读取标题草稿、简介、文稿和字幕。
     */
    LOAD_CONTEXT,

    /**
     * 调用 LLM 生成发布前优化建议。
     */
    LLM_CALL,

    /**
     * 保存结构化建议和结果消息。
     */
    SAVE_RESULT,

    /**
     * 用户确认采用某轮发布前建议。
     */
    CONFIRM_RESULT
}
