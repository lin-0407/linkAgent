package com.link.linkagent.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank(message = "消息内容不能为空")
        String message
) {
}
