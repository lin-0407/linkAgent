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

/** 固定指纹与金额规则，避免 JSON 键顺序和浮点舍入改变幂等或预算含义。 */
final class CreationInputRules {
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
        try {
            if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 65536) throw conflict("输入内容过大或为空");
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
    static ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT, reason); }
}
