package com.link.linkagent.core;

/**
 * 单次 ReAct 迭代的完整记录，便于返回给前端调试。
 */
public record AgentStep(
        int stepNumber,
        String thought,
        String action,
        String actionInput,
        String observation
) {
}
