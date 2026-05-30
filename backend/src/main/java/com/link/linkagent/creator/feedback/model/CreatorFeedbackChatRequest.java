package com.link.linkagent.creator.feedback.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 评论弹幕追问请求。
 * 只允许用户提交问题本身，避免前端把证据上下文或系统规则暴露成可篡改输入。
 */
public record CreatorFeedbackChatRequest(
        @NotBlank(message = "追问问题不能为空")
        @Size(max = 1000, message = "追问问题长度不能超过1000个字符")
        String question
) {
}
