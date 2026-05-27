package com.link.linkagent.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmJsonUtilTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldExtractJsonObjectFromLlmOutput() {
        String json = LlmJsonUtil.extractJsonObject("说明文字 {\"name\":\"标题\",\"tags\":[\"Java\"]} 结尾");

        assertThat(json).isEqualTo("{\"name\":\"标题\",\"tags\":[\"Java\"]}");
    }

    @Test
    void shouldReadTextAndJsonField() throws Exception {
        JsonNode rootNode = objectMapper.readTree("{\"name\":\"标题\",\"tags\":[\"Java\",\"AI\"],\"count\":3}");

        assertThat(LlmJsonUtil.text(rootNode, "name")).isEqualTo("标题");
        assertThat(LlmJsonUtil.text(rootNode, "count")).isEqualTo("3");
        assertThat(LlmJsonUtil.json(objectMapper, rootNode, "tags")).isEqualTo("[\"Java\",\"AI\"]");
    }

    @Test
    void shouldRejectOutputWithoutJsonObject() {
        assertThatThrownBy(() -> LlmJsonUtil.extractJsonObject("不是 JSON"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LLM 输出中没有 JSON 对象");
    }
}
