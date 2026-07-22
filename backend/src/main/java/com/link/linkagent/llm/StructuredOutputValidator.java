package com.link.linkagent.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 结构化结果字段校验器。
 * JSON_OBJECT 只保证语法合法，这里统一拒绝缺失字段，才能让反序列化异常进入现有重试链路。
 */
public final class StructuredOutputValidator {

    private StructuredOutputValidator() {
    }

    public static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("结构化输出缺少必填字段：" + fieldName);
        }
        return value.trim();
    }

    public static <T> List<T> requireList(List<T> values, String fieldName) {
        if (values == null) {
            throw new IllegalArgumentException("结构化输出缺少必填字段：" + fieldName);
        }
        if (values.stream().anyMatch(item -> item == null)) {
            throw new IllegalArgumentException("结构化输出字段包含空元素：" + fieldName);
        }
        return List.copyOf(values);
    }

    public static List<String> requireTextList(List<String> values, String fieldName) {
        List<String> requiredValues = requireList(values, fieldName);
        List<String> normalizedValues = new ArrayList<>(requiredValues.size());
        for (int index = 0; index < requiredValues.size(); index++) {
            normalizedValues.add(requireText(requiredValues.get(index), fieldName + "[" + index + "]"));
        }
        return List.copyOf(normalizedValues);
    }
}
