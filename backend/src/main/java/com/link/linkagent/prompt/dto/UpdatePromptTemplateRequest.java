package com.link.linkagent.prompt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 改写提示词正文的请求体（5.5-2a 写接口用）。
 * 沿用项目请求 DTO 的 record + Jakarta 校验风格；只允许改正文，key 走路径参数，类型/场景不在本接口改。
 * 上限 20000 字是给运行期再加一道入参护栏（正文列本身是 LONGTEXT），防止误贴超长内容。
 */
public record UpdatePromptTemplateRequest(
        @NotBlank(message = "提示词正文不能为空")
        @Size(max = 20000, message = "提示词正文长度不能超过20000个字符")
        String content
) {
}
