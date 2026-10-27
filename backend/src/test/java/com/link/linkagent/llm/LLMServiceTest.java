package com.link.linkagent.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LLMServiceTest {

    @Test
    void shouldRejectPromptWhenGuardEnabledAndPromptTooLong() {
        LlmCallGuardProperties properties = new LlmCallGuardProperties();
        properties.setEnabled(true);
        // 上限 1 token：任何正常中英文输入都会超过它，这里验证的是「按 token 拦截」这条路径本身。
        properties.setMaxPromptTokens(1);
        LLMService service = new LLMService(properties);

        assertThatThrownBy(() -> service.validatePromptLength("这是一段系统提示词", "这是一段用户输入"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("输入过长");
    }

    @Test
    void shouldAllowPromptWhenGuardDisabled() {
        LlmCallGuardProperties properties = new LlmCallGuardProperties();
        properties.setEnabled(false);
        properties.setMaxPromptTokens(1);
        LLMService service = new LLMService(properties);

        assertThatCode(() -> service.validatePromptLength("system", "123456789012345"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldNotDisableGuardWhenMaxPromptTokensIsZero() {
        LlmCallGuardProperties properties = new LlmCallGuardProperties();
        properties.setEnabled(true);
        properties.setMaxPromptTokens(0);
        LLMService service = new LLMService(properties);

        assertThatThrownBy(() -> service.validatePromptLength("system", "user"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("当前限制约 1 token");
    }

    @Test
    void shouldChooseAutoToolChoiceWhenThinkingEnabled() {
        LLMService service = new LLMService(new LlmCallGuardProperties());

        // 2026-09-19 真实接口实测：思考模式传指定函数或 required 都会 400，只有 auto 可用；
        // 这一路只挂一个结果函数，auto 的实际效果仍是模型用该函数交结果。
        assertThat(service.structuredResultToolChoice(true)).isEqualTo("auto");
    }

    @Test
    void shouldKeepForcedResultFunctionWhenThinkingDisabled() {
        LLMService service = new LLMService(new LlmCallGuardProperties());

        Object toolChoice = service.structuredResultToolChoice(false);

        assertThat(toolChoice).isInstanceOf(Map.class);
        Map<?, ?> choice = (Map<?, ?>) toolChoice;
        assertThat(choice.get("type")).isEqualTo("function");
        assertThat(choice.get("function")).isInstanceOf(Map.class);
    }

    @Test
    void shouldTreatZeroUsageAsUnknownTokens() {
        LLMService service = new LLMService(new LlmCallGuardProperties());
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(0);
        when(usage.getCompletionTokens()).thenReturn(0);
        when(usage.getTotalTokens()).thenReturn(0);

        assertThat(service.extractPromptTokens(usage)).isNull();
        assertThat(service.extractCompletionTokens(usage)).isNull();
        assertThat(service.extractTotalTokens(usage)).isNull();
    }

    @Test
    void shouldExtractUsageTokensWhenPresent() {
        LLMService service = new LLMService(new LlmCallGuardProperties());
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(12);
        when(usage.getCompletionTokens()).thenReturn(8);
        when(usage.getTotalTokens()).thenReturn(20);

        assertThat(service.extractPromptTokens(usage)).isEqualTo(12);
        assertThat(service.extractCompletionTokens(usage)).isEqualTo(8);
        assertThat(service.extractTotalTokens(usage)).isEqualTo(20);
    }
}
