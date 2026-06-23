package com.link.linkagent.core.citation;

/**
 * 证据来源类型。
 * <p>
 * 来源类型用于约束 Synthesizer 只能基于可回溯材料下结论，避免把模型自己的推断伪装成事实。
 */
public enum EvidenceSourceType {
    TOOL_OBSERVATION,
    PLAN_STEP,
    USER_INPUT,
    CONVERSATION_CONTEXT,
    WORKER_REASONING,
    SYSTEM_LIMITATION
}
