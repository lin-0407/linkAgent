package com.link.linkagent.dto;

import com.link.linkagent.core.AgentStep;

import java.util.List;

/**
 * Agent 聊天响应，包含最终答案及完整步骤追踪。
 */
public record AgentChatResponse(
        String sessionId,
        String finalAnswer,
        String stopReason,
        int totalSteps,
        List<AgentStep> steps
) {
}
