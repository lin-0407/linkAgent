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
        child.put("properties", Map.of(
                "name", Map.of("type", "string", "maxLength", 32),
                "tags", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1)
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        schema.put("properties", Map.of("child", child));

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
