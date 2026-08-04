package com.link.linkagent.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Spring AI 生成的 JSON Schema 收敛到 DeepSeek strict 模式支持的子集。
 *
 * DeepSeek 要求每层对象的全部属性都进入 required，且 additionalProperties 必须为 false；
 * 同时不支持字符串长度和数组长度关键字，因此这里在发送请求前统一规范化，避免 Beta 接口直接返回 400。
 */
final class DeepSeekStrictJsonSchema {

    private static final List<String> UNSUPPORTED_KEYWORDS = List.of(
            "$schema", "title", "minLength", "maxLength", "minItems", "maxItems"
    );

    private DeepSeekStrictJsonSchema() {
    }

    static Map<String, Object> normalize(Map<String, Object> schema, ObjectMapper objectMapper) {
        ObjectNode root = objectMapper.valueToTree(schema);
        normalizeNode(root);
        return objectMapper.convertValue(root, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private static void normalizeNode(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            node.forEach(DeepSeekStrictJsonSchema::normalizeNode);
            return;
        }
        if (!node.isObject()) {
            return;
        }

        ObjectNode object = (ObjectNode) node;
        object.remove(UNSUPPORTED_KEYWORDS);
        JsonNode properties = object.get("properties");
        if ("object".equals(object.path("type").asText()) && properties != null && properties.isObject()) {
            ArrayNode required = object.putArray("required");
            properties.fieldNames().forEachRemaining(required::add);
            object.put("additionalProperties", false);
        }

        List<JsonNode> children = new ArrayList<>();
        object.elements().forEachRemaining(children::add);
        children.forEach(DeepSeekStrictJsonSchema::normalizeNode);
    }
}
