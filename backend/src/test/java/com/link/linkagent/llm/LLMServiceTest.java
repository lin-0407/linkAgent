package com.link.linkagent.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.web.server.ResponseStatusException;

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
        properties.setMaxPromptChars(10);
        LLMService service = new LLMService(properties);

        assertThatThrownBy(() -> service.validatePromptLength("system", "123456"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("输入过长");
    }

    @Test
    void shouldAllowPromptWhenGuardDisabled() {
        LlmCallGuardProperties properties = new LlmCallGuardProperties();
        properties.setEnabled(false);
        properties.setMaxPromptChars(10);
        LLMService service = new LLMService(properties);

        assertThatCode(() -> service.validatePromptLength("system", "123456789012345"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldNotDisableGuardWhenMaxPromptCharsIsZero() {
        LlmCallGuardProperties properties = new LlmCallGuardProperties();
        properties.setEnabled(true);
        properties.setMaxPromptChars(0);
        LLMService service = new LLMService(properties);

        assertThatThrownBy(() -> service.validatePromptLength("system", "user"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("当前限制为 1 个字符");
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
