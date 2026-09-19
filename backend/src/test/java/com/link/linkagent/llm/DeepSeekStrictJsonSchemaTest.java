package com.link.linkagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekStrictJsonSchemaTest {

    @Test
    void shouldRequireEveryObjectPropertyAndRemoveUnsupportedKeywords() {
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("type", "object");
        // 属性必须用 LinkedHashMap 而不是 Map.of：Map.of 的迭代顺序在每个 JVM 启动时被随机化（集合内部加盐），
        // 而 normalize 是按属性出现顺序生成 required 的，用 Map.of 会让下面的顺序断言随机翻转（实测已翻转成 tags, name）。
        Map<String, Object> childProperties = new LinkedHashMap<>();
        childProperties.put("name", Map.of("type", "string", "maxLength", 32));
        childProperties.put("tags", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1));
        child.put("properties", childProperties);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        // 同上：这里也用有序 Map，避免以后多加几个属性时再出现同类随机顺序
        Map<String, Object> schemaProperties = new LinkedHashMap<>();
        schemaProperties.put("child", child);
        schema.put("properties", schemaProperties);

        Map<String, Object> normalized = DeepSeekStrictJsonSchema.normalize(schema, new ObjectMapper());

        assertThat(normalized).doesNotContainKey("$schema");
        assertThat(normalized.get("required")).isEqualTo(List.of("child"));
        assertThat(normalized.get("additionalProperties")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) normalized.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> normalizedChild = (Map<String, Object>) properties.get("child");
        assertThat(normalizedChild.get("required")).isEqualTo(List.of("name", "tags"));
        assertThat(normalizedChild.get("additionalProperties")).isEqualTo(false);
        assertThat(normalizedChild.toString()).doesNotContain("maxLength", "minItems");
    }
}
