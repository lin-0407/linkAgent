package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeSet;
import java.util.Set;
import com.link.linkagent.creator.creation.model.GeneratedDraftInput;

/** 固定指纹与金额规则，避免 JSON 键顺序和浮点舍入改变幂等或预算含义。 */
public final class CreationInputRules {
    private CreationInputRules() { }
    static BigDecimal money(BigDecimal value, boolean allowZero) {
        if (value == null || value.scale() > 6 || (long) value.precision() - value.scale() > 12
                || value.signum() < 0 || (!allowZero && value.signum() == 0)) throw conflict("金额格式或范围不正确");
        return value.setScale(6);
    }
    static String key(String value) {
        if (value == null || value.isBlank() || value.length() > 128) throw conflict("幂等键或调用标识不正确");
        return value;
    }
    static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) throw conflict("业务标识不正确");
        return value;
    }
    static String evidence(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) throw conflict("费用依据必须是有效日志标识");
        return value;
    }
    static String canonical(ObjectMapper mapper, String value) {
        return canonical(mapper, value, 65536);
    }
    static String renderDocument(ObjectMapper mapper, String value) {
        return canonical(mapper, value, 256 * 1024);
    }
    public static String renderInputHash(ObjectMapper mapper, ObjectNode document) {
        try {
            ObjectNode content = document.deepCopy(); content.remove("inputHash");
            return renderFingerprint(mapper, mapper.readTree(renderDocument(mapper, mapper.writeValueAsString(sorted(mapper, content, 0)))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw conflict("工程内容无效"); }
    }

    private static String renderFingerprint(ObjectMapper mapper, JsonNode node) throws com.fasterxml.jackson.core.JsonProcessingException {
        // JSON 仍保存数字；仅摘要统一使用浮点位模式，避免两种语言的科学记数格式不同。
        return hash(renderTokens(mapper, node, 0));
    }

    private static String renderTokens(ObjectMapper mapper, JsonNode node, int depth) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (depth > 20) throw conflict("输入层级过深");
        if (node.isObject()) {
            var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            var entries = new java.util.ArrayList<String>();
            for (String name : names) entries.add(mapper.writeValueAsString(name) + ":" + renderTokens(mapper, node.get(name), depth + 1));
            return "{" + String.join(",", entries) + "}";
        }
        if (node.isArray()) {
            var entries = new java.util.ArrayList<String>();
            for (JsonNode item : node) entries.add(renderTokens(mapper, item, depth + 1));
            return "[" + String.join(",", entries) + "]";
        }
        if (node.isFloatingPointNumber()) {
            double value = node.doubleValue();
            if (!Double.isFinite(value)) throw conflict("工程数值必须有限");
            return "f64:" + HexFormat.of().toHexDigits(Double.doubleToRawLongBits(value));
        }
        return mapper.writeValueAsString(node);
    }
    private static String canonical(ObjectMapper mapper, String value, int maxBytes) {
        try {
            if (value == null || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw conflict("输入内容过大或为空");
            JsonNode node = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
            if (node == null || !node.isObject() || node.isEmpty()) throw conflict("输入必须是非空结构化对象");
            return mapper.writeValueAsString(sorted(mapper, node, 0));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw conflict("输入不是有效的结构化内容");
        }
    }
    private static JsonNode sorted(ObjectMapper mapper, JsonNode node, int depth) {
        if (depth > 20) throw conflict("输入层级过深");
        if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) throw conflict("输入数值必须有限");
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, sorted(mapper, node.get(name), depth + 1)));
            return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode();
            node.forEach(item -> result.add(sorted(mapper, item, depth + 1)));
            return result;
        }
        return node;
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    static GeneratedDraftInput productionInput(ObjectMapper mapper, String value) {
        try {
            JsonNode node = mapper.readTree(canonical(mapper, value));
            Set<String> fields = new TreeSet<>(); node.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of("publishingSessionId", "publishingResultId", "productionPlanId",
                    "baseVersionId", "storyboardId", "renderContentHash"))) throw conflict("整片制作依据字段不完整");
            for (String name : Set.of("publishingSessionId", "publishingResultId", "productionPlanId", "storyboardId"))
                id(node.path(name).isTextual() ? node.path(name).textValue() : null);
            if (!node.path("baseVersionId").isNull()) id(node.path("baseVersionId").textValue());
            String hash = node.path("renderContentHash").asText();
            if (!hash.matches("[0-9a-f]{64}")) throw conflict("工程内容摘要不正确");
            return new GeneratedDraftInput(node.path("publishingSessionId").textValue(), node.path("publishingResultId").textValue(),
                    node.path("productionPlanId").textValue(), node.path("baseVersionId").textValue(), node.path("storyboardId").textValue(), hash);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw conflict("整片制作依据无效"); }
    }

    public static String renderContentHash(ObjectMapper mapper, String document) {
        try {
            // 渲染工程沿用 S2 的 256KiB 上限，不能被运行控制请求的 64KiB 上限意外截断。
            ObjectNode content = (ObjectNode) mapper.readTree(canonical(mapper, document, 256 * 1024));
            content.remove(java.util.List.of("runId", "attemptId", "inputHash"));
            return renderFingerprint(mapper, content);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw conflict("工程内容无效"); }
    }
    static ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT, reason); }
}
