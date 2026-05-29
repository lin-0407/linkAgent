package com.link.linkagent.creator.workflow.model;

/**
 * 工作流消息角色。
 * 使用创作者业务语义，而不是直接复用通用对话 role，方便前端区分过程、输入和结果卡片。
 */
public enum CreatorWorkflowMessageRole {
    /**
     * 系统过程消息：例如已进入阶段、已加载任务材料。
     */
    SYSTEM,

    /**
     * 用户输入消息：例如用户补充的标题风格、分析要求。
     */
    USER,

    /**
     * Agent 分析消息：例如 Agent 对下一步分析动作的说明。
     */
    AGENT,

    /**
     * 工具执行结果消息：后续接入工具调用后，用于展示工具返回摘要。
     */
    TOOL,

    /**
     * 结构化结果消息：例如发布前建议卡片、反馈分析结论。
     */
    RESULT
}
