package com.link.linkagent.memory;

import com.link.linkagent.llm.ChatRequestEnvelope;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.LlmCallResult;
import com.link.linkagent.llm.TokenCounter;
import com.link.linkagent.prompt.StubPromptService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 摘要生成的硬约束：被输出上限截断、或正文超出 token 预算时，必须直接判失败，不能落库/裁剪。
 *
 * <p>背景：工具证据型会话里模型会试图把几百条 BV/指标逐条搬进摘要，输出膨胀到几万 token 再被截断；
 * 如果这种半截摘要进入复核，只会得到“漏抽”并触发更贵的重生成。这里锁住 fail-closed 行为。
 */
class SummaryMemoryGuardTest {

    @Test
    void shouldRejectSummaryTruncatedByOutputLimit() {
        LLMService fakeLlm = new LLMService() {
            @Override
            public LlmCallResult replayChat(ChatRequestEnvelope envelope, List<Message> messages,
                                            Integer maxOutputTokens) {
                // finish_reason=length：输出被 max_tokens 截断
                return new LlmCallResult("半截摘要", "test-model", 100, 10, 110, 0, "length", 1L);
            }
        };
        SummaryMemory memory = memory(fakeLlm, null);

        String result = memory.summarize("session-truncated", List.of(new MemoryMessage("Human", "历史消息")));

        assertThat(result).isNull();
        assertThat(memory.getSummary("session-truncated")).isEmpty();
    }

    @Test
    void shouldRejectSummaryOverTokenBudget() {
        LLMService fakeLlm = new LLMService() {
            @Override
            public LlmCallResult replayChat(ChatRequestEnvelope envelope, List<Message> messages,
                                            Integer maxOutputTokens) {
                return new LlmCallResult("一份很长但没被截断的摘要", "test-model", 100, 9000, 9100, 0, "stop", 1L);
            }
        };
        TokenCounter counter = mock(TokenCounter.class);
        when(counter.count(anyString())).thenReturn(99_999);
        SummaryMemory memory = memory(fakeLlm, counter);

        String result = memory.summarize("session-too-large", List.of(new MemoryMessage("Human", "历史消息")));

        assertThat(result).isNull();
        assertThat(memory.getSummary("session-too-large")).isEmpty();
    }

    private SummaryMemory memory(LLMService fakeLlm, TokenCounter tokenCounter) {
        return new SummaryMemory(
                new SummaryMemoryProperties(true, 256_000, 2),
                prompt -> {
                    throw new IllegalStateException("有 LLMService 时应走信封重放，不应直连摘要模型");
                },
                new StubPromptService(), null, null, null, null, null, fakeLlm, tokenCounter);
    }
}
