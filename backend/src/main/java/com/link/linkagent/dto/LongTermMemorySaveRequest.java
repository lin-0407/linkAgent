package com.link.linkagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LongTermMemorySaveRequest(
        @NotBlank(message = "用户ID不能为空")
        @Size(max = 64, message = "用户ID长度不能超过64个字符")
        String userId,

        @NotBlank(message = "记忆键不能为空")
        @Size(max = 128, message = "记忆键长度不能超过128个字符")
        String memoryKey,

        @NotBlank(message = "记忆内容不能为空")
        @Size(max = 2000, message = "记忆内容长度不能超过2000个字符")
        String content,

        @Size(max = 64, message = "来源会话ID长度不能超过64个字符")
        String sourceSessionId
) {
}
