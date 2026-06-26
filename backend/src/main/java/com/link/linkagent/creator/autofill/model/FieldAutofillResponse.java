package com.link.linkagent.creator.autofill.model;

/**
 * 字段自动补全响应。
 */
public record FieldAutofillResponse(
        String fieldType,
        String suggestion
) {
}
