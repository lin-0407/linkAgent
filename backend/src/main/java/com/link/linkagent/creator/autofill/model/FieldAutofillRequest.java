package com.link.linkagent.creator.autofill.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 字段自动补全请求。
 * 前端输入框旁的 AI 按钮点击后发送，后端根据任务全局上下文生成补全建议。
 */
public record FieldAutofillRequest(
        @NotBlank(message = "字段类型不能为空")
        @Pattern(
                regexp = "TITLE_DRAFT|DESCRIPTION_DRAFT|CUSTOM_GUIDANCE|TITLE_STYLE|EXTRA_REQUIREMENT",
                message = "字段类型只能是 TITLE_DRAFT、DESCRIPTION_DRAFT、CUSTOM_GUIDANCE、TITLE_STYLE 或 EXTRA_REQUIREMENT"
        )
        String fieldType
) {
}
