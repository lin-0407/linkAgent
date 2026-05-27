package com.link.linkagent.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * LLM JSON 输出解析工具。
 * 大模型偶尔会在 JSON 前后夹带说明文本，所以统一先截取最外层对象再交给 Jackson 解析。
 */
public final class LlmJsonUtil {

    private LlmJsonUtil() {
    }

    public static String extractJsonObject(String rawOutput) {
        String normalized = TextUtil.trimToDefault(rawOutput, "");
        int startIndex = normalized.indexOf('{');
        int endIndex = normalized.lastIndexOf('}');
        if (startIndex < 0 || endIndex <= startIndex) {
            throw new IllegalArgumentException("LLM 输出中没有 JSON 对象");
        }
        return normalized.substring(startIndex, endIndex + 1);
    }

    public static String text(JsonNode rootNode, String fieldName) {
        JsonNode valueNode = rootNode.get(fieldName);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        if (valueNode.isTextual()) {
            return valueNode.asText();
        }
        return valueNode.toString();
    }

    public static String json(ObjectMapper objectMapper, JsonNode rootNode, String fieldName) throws JsonProcessingException {
        JsonNode valueNode = rootNode.get(fieldName);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        return objectMapper.writeValueAsString(valueNode);
    }
}
