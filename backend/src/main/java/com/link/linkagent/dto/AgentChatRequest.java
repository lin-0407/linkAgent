package com.link.linkagent.dto;

import com.link.linkagent.core.AgentExecutionMode;
import com.link.linkagent.assistant.AssistantModels.ChatContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentChatRequest(
        @Size(max = 64, message = "会话ID长度不能超过64个字符")
        String sessionId,

        @Size(max = 64, message = "用户ID长度不能超过64个字符")
        String userId,

        @NotBlank(message = "消息内容不能为空")
        String message,

        AgentExecutionMode executionMode,

        @Valid ChatContext context
) {
    public AgentChatRequest(String sessionId, String userId, String message, AgentExecutionMode executionMode) {
        this(sessionId, userId, message, executionMode, null);
    }
}
