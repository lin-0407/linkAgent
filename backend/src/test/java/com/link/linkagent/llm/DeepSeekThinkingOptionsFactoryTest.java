package com.link.linkagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.settings.service.RuntimeSettingService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeepSeekThinkingOptionsFactoryTest {

    private final DeepSeekThinkingProperties properties = new DeepSeekThinkingProperties();
    private final RuntimeSettingService runtimeSettingService = mock(RuntimeSettingService.class);
    private final DeepSeekThinkingOptionsFactory factory = new DeepSeekThinkingOptionsFactory(
            properties,
            runtimeSettingService
    );

    @Test
    void shouldSendOfficialMaxThinkingFieldsForDeepSeekFlash() {
        when(runtimeSettingService.isDeepSeekThinkingEnabled()).thenReturn(true);
        when(runtimeSettingService.getDeepSeekReasoningEffort()).thenReturn("max");

        OpenAiChatOptions options = factory.optionsForModel("deepseek-v4-flash");

        assertThat(options.getReasoningEffort()).isEqualTo("max");
        assertThat(options.getExtraBody())
                .containsEntry("thinking", java.util.Map.of("type", "enabled"));
    }

    @Test
    void shouldNotSendDeepSeekFieldsForOtherModels() {
        OpenAiChatOptions options = factory.optionsForModel("deepseek-chat");

        assertThat(options.getReasoningEffort()).isNull();
        assertThat(options.getExtraBody()).isNull();
    }

    @Test
    void shouldSendDisabledThinkingTypeWithoutReasoningEffort() {
        when(runtimeSettingService.isDeepSeekThinkingEnabled()).thenReturn(false);

        OpenAiChatOptions options = factory.optionsForModel("deepseek-v4-flash");

        assertThat(options.getReasoningEffort()).isNull();
        assertThat(options.getExtraBody())
                .containsEntry("thinking", java.util.Map.of("type", "disabled"));
    }

    @Test
    void shouldUseTheSameOfficialFieldsForFallbackJson() throws Exception {
        when(runtimeSettingService.isDeepSeekThinkingEnabled()).thenReturn(true);
        when(runtimeSettingService.getDeepSeekReasoningEffort()).thenReturn("max");
        ObjectNode request = new ObjectMapper().createObjectNode();

        factory.applyToRequest(request, "deepseek-v4-flash");

        assertThat(request.get("reasoning_effort").asText()).isEqualTo("max");
        assertThat(request.at("/thinking/type").asText()).isEqualTo("enabled");
    }

    @Test
    void shouldRejectUnsupportedReasoningEffort() {
        assertThat(DeepSeekThinkingProperties.normalizeReasoningEffort("xhigh")).isNull();
        assertThat(DeepSeekThinkingProperties.normalizeReasoningEffort("MAX")).isEqualTo("max");
    }
}
