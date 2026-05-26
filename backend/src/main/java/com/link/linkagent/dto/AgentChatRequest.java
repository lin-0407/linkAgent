package com.link.linkagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentChatRequest(
        @Size(max = 64, message = "会话ID长度不能超过64个字符")
        String sessionId,

        @Size(max = 64, message = "用户ID长度不能超过64个字符")
        String userId,

        @NotBlank(message = "消息内容不能为空")
        String message
) {
}
