package com.link.linkagent.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AgentChatRequest(
        @NotBlank(message = "消息内容不能为空")
        String message
) {
}
