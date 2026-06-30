package com.link.linkagent.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * LLM JSON 输出解析工具。
 * <p>
 * <b>为什么需要独立工具类：</b>
 * 大模型（DeepSeek 等）的实际输出常常不符合严格的 JSON-only 契约：
 * <ul>
 *   <li>JSON 前后夹带自然语言说明文本（如 "好的，以下是分析结果：{...}"）</li>
 *   <li>嵌套结构中个别字段可能返回对象而非字符串</li>
 * </ul>
 * 如果直接用 Jackson 解析原始输出会抛 {@link JsonProcessingException}，
 * 本工具统一做预处理（截取最外层 { }）后再交给 Jackson，降低调用方的解析脆弱性。
 * <p>
 * 设计约束：工具类不可实例化，全部为纯函数式方法。
 */
public final class LlmJsonUtil {

    /** 私有构造器，防止外部实例化工具类。 */
    private LlmJsonUtil() {
    }

    /**
     * 从 LLM 原始输出中截取最外层的 JSON 对象字符串（{ ... }）。
     * <p>
     * <b>算法思路：</b>
     * 找到第一个 '{' 和最后一个 '}'，取中间子串。
     * 这个简单的贪心匹配在绝大多数场景足够（正常 JSON 的输出中 '{' 和 '}' 一一对应），
     * 不需要引入完整的 JSON 流式解析器。
     * <p>
     * <b>边界条件：</b>
     * 如果输出中没有 '{' 或 '}' 位置不合法（无 '{' 或 '}' 在 '{' 之前），
     * 抛出 {@link IllegalArgumentException} 而非返回 null ——
     * 这表示 LLM 根本没输出 JSON，属于需要上游介入的异常情况。
     *
     * @param rawOutput LLM 原始输出字符串，可为空白
     * @return 从第一个 '{' 到最后一个 '}'（含）的子串
     * @throws IllegalArgumentException 如果输出中找不到合法的 JSON 对象边界
     */
    public static String extractJsonObject(String rawOutput) {
        String normalized = TextUtil.trimToDefault(rawOutput, "");
        int startIndex = normalized.indexOf('{');
        int endIndex = normalized.lastIndexOf('}');
        if (startIndex < 0 || endIndex <= startIndex) {
            throw new IllegalArgumentException("LLM 输出中没有 JSON 对象");
        }
        return normalized.substring(startIndex, endIndex + 1);
    }

    /**
     * 从 JsonNode 中安全提取字段的文本值。
     * <p>
     * <b>为什么需要类型适配：</b>
     * LLM 有时会在本该返回字符串的字段中嵌入 JSON 对象或数组
     * （如 summary 字段返回 {"key": "val"}）。
     * 此时 {@code asText()} 会抛异常或返回空字符串。
     * 本方法对非文本节点回退到 {@code toString()}，保证调用方总能拿到可用的字符串。
     *
     * @param rootNode  JSON 对象节点
     * @param fieldName 字段名
     * @return 字段的字符串值，或 null（字段不存在/值为 null）
     */
    public static String text(JsonNode rootNode, String fieldName) {
        JsonNode valueNode = rootNode.get(fieldName);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        if (valueNode.isTextual()) {
            return valueNode.asText();
        }
        // 非文本节点（对象/数组等），回退到 toString 保证不丢数据
        return valueNode.toString();
    }

    /**
     * 从 JsonNode 中提取字段并序列化为 JSON 字符串。
     * <p>
     * 与 {@link #text(JsonNode, String)} 的区别：
     * 本方法始终返回合法的 JSON 字符串（文本字段会被加引号转义），
     * 适用于需要保留原始 JSON 结构用于二次解析的场景。
     *
     * @param objectMapper Jackson ObjectMapper 实例（由调用方传入以复用全局单例）
     * @param rootNode     JSON 对象节点
     * @param fieldName    字段名
     * @return 字段值的 JSON 字符串表示，或 null（字段不存在/值为 null）
     * @throws JsonProcessingException 如果 Jackson 序列化失败（理论上不应该发生）
     */
    public static String json(ObjectMapper objectMapper, JsonNode rootNode, String fieldName) throws JsonProcessingException {
        JsonNode valueNode = rootNode.get(fieldName);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        return objectMapper.writeValueAsString(valueNode);
    }
}
