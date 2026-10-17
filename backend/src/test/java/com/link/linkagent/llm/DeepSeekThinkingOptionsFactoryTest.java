package com.link.linkagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @ParameterizedTest
    @CsvSource({"评测回答,max", "评测裁判,low", "长期记忆抽取,low", "长期记忆写入决策,low",
            "长期记忆重要性打分,low", "摘要生成,low", "摘要复核,low", "摘要事实点抽取,low"})
    void shouldUseRequestedEffortForBothPrimaryAndFallbackRequests(String scene, String effort) {
        when(runtimeSettingService.isDeepSeekThinkingEnabled()).thenReturn(true);
        when(runtimeSettingService.getDeepSeekReasoningEffort()).thenReturn("high");
        try (var ignored = LlmUsageContext.scene(scene)) {
            OpenAiChatOptions options = factory.optionsForModel("deepseek-v4-flash");
            ObjectNode fallback = new ObjectMapper().createObjectNode();
            factory.applyToRequest(fallback, "deepseek-v4-flash");

            assertThat(options.getReasoningEffort()).isEqualTo(effort);
            assertThat(options.getExtraBody()).containsEntry("thinking", java.util.Map.of("type", "enabled"));
            assertThat(fallback.path("reasoning_effort").asText()).isEqualTo(effort);
            assertThat(factory.reasoningEffortForLog("deepseek-v4-flash")).isEqualTo(effort);
        }
    }

    @Test
    void shouldRestoreAnswerEffortAfterNestedMemoryWorkAndNotLeakToOtherRequests() {
        when(runtimeSettingService.isDeepSeekThinkingEnabled()).thenReturn(true);
        when(runtimeSettingService.getDeepSeekReasoningEffort()).thenReturn("high");
        try (var answer = LlmUsageContext.scene("评测回答")) {
            try (var memory = LlmUsageContext.scene("长期记忆抽取")) {
                assertThat(factory.optionsForModel("deepseek-v4-flash").getReasoningEffort()).isEqualTo("low");
            }
            assertThat(factory.optionsForModel("deepseek-v4-flash").getReasoningEffort()).isEqualTo("max");
        }
        assertThat(factory.optionsForModel("deepseek-v4-flash").getReasoningEffort()).isEqualTo("high");
    }
}
