package com.link.linkagent.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StrictFunctionCallingClientTest {

    @Test
    void shouldReportThinkingEnabledForStrictModel() {
        StrictFunctionCallingProperties properties = new StrictFunctionCallingProperties();
        properties.setModel("deepseek-v4-flash");
        DeepSeekThinkingOptionsFactory thinkingOptionsFactory = mock(DeepSeekThinkingOptionsFactory.class);
        when(thinkingOptionsFactory.isThinkingEnabled("deepseek-v4-flash")).thenReturn(true);

        StrictFunctionCallingClient client = new StrictFunctionCallingClient(properties, thinkingOptionsFactory);

        // 思考开着时 LLMService 必须改用 auto tool_choice，否则会撞 400
        assertThat(client.isThinkingEnabled()).isTrue();
    }

    @Test
    void shouldReportThinkingDisabledWhenModelDoesNotUseThinking() {
        StrictFunctionCallingProperties properties = new StrictFunctionCallingProperties();
        properties.setModel("deepseek-chat");
        DeepSeekThinkingOptionsFactory thinkingOptionsFactory = mock(DeepSeekThinkingOptionsFactory.class);
        when(thinkingOptionsFactory.isThinkingEnabled("deepseek-chat")).thenReturn(false);

        StrictFunctionCallingClient client = new StrictFunctionCallingClient(properties, thinkingOptionsFactory);

        assertThat(client.isThinkingEnabled()).isFalse();
    }

    @Test
    void shouldDisableThinkingAndLimitOutputWhenRequested() {
        StrictFunctionCallingProperties properties = new StrictFunctionCallingProperties();
        properties.setModel("deepseek-v4-flash");
        DeepSeekThinkingOptionsFactory thinkingOptionsFactory = mock(DeepSeekThinkingOptionsFactory.class);
        when(thinkingOptionsFactory.optionsForModel("deepseek-v4-flash"))
                .thenReturn(OpenAiChatOptions.builder().reasoningEffort("max").build());
        StrictFunctionCallingClient client = new StrictFunctionCallingClient(properties, thinkingOptionsFactory);

        OpenAiChatOptions options = client.buildOptions(List.of(), "auto", true, 512);

        // 打分这类轻量调用要求显式关思考并限长：思考参数必须被清掉，否则请求还是慢链路
        assertThat(options.getReasoningEffort()).isNull();
        assertThat(options.getExtraBody()).containsEntry("thinking", Map.of("type", "disabled"));
        assertThat(options.getMaxTokens()).isEqualTo(512);
    }

    @Test
    void shouldKeepThinkingOptionsWhenNotRequested() {
        StrictFunctionCallingProperties properties = new StrictFunctionCallingProperties();
        properties.setModel("deepseek-v4-flash");
        DeepSeekThinkingOptionsFactory thinkingOptionsFactory = mock(DeepSeekThinkingOptionsFactory.class);
        when(thinkingOptionsFactory.optionsForModel("deepseek-v4-flash"))
                .thenReturn(OpenAiChatOptions.builder().reasoningEffort("max").build());
        StrictFunctionCallingClient client = new StrictFunctionCallingClient(properties, thinkingOptionsFactory);

        OpenAiChatOptions options = client.buildOptions(List.of(), "auto", false, null);

        // 默认路径不能被这次改动碰到：摘要、复核、事实点抽取仍按全局思考设置走
        assertThat(options.getReasoningEffort()).isEqualTo("max");
        assertThat(options.getMaxTokens()).isNull();
    }
}
