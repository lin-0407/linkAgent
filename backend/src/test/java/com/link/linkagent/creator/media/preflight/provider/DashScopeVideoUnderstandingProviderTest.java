package com.link.linkagent.creator.media.preflight.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证视频理解兼容接口的请求和用量解析，避免真实联调时才发现协议字段错位。 */
class DashScopeVideoUnderstandingProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldBuildVideoRequestAndParseStructuredContent() throws Exception {
        DashScopeVideoUnderstandingProvider provider = new DashScopeVideoUnderstandingProvider(
                null, null, objectMapper
        );

        var request = provider.buildRequest("https://media.example/review.mp4", "检查开场");
        var result = provider.parseResponse(objectMapper.readTree("""
                {
                  "choices": [{"message": {"content": "{\\\"executiveSummary\\\":\\\"开场价值明确\\\",\\\"issues\\\":[]}"}}],
                  "usage": {"prompt_tokens": 1200, "completion_tokens": 180}
                }
                """));

        assertThat(request.at("/messages/0/content/0/type").asText()).isEqualTo("video_url");
        assertThat(request.at("/messages/0/content/0/fps").asDouble()).isEqualTo(0.2d);
        assertThat(request.at("/enable_thinking").asBoolean()).isFalse();
        assertThat(result.content()).contains("开场价值明确");
        assertThat(result.inputTokens()).isEqualTo(1200L);
        assertThat(result.outputTokens()).isEqualTo(180L);
    }
}
