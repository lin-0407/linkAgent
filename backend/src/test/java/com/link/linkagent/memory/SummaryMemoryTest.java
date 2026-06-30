package com.link.linkagent.memory;

import com.link.linkagent.prompt.StubPromptService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryMemoryTest {

    @Test
    void shouldReturnEmptySummaryWhenDisabled() {
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(false, 8, 2), fixedSummaryModel("summary"), new StubPromptService());

        memory.saveSummary("session-1", "user likes Java");

        assertThat(memory.getSummary("session-1")).isEmpty();
    }

    @Test
    void shouldKeepManualSummaryWhenEnabled() {
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, 8, 2), fixedSummaryModel("summary"), new StubPromptService());

        memory.saveSummary("session-1", " user likes Java ");

        assertThat(memory.getSummary("session-1")).isEqualTo("user likes Java");
    }

    @Test
    void shouldNotSummarizeWhenMessageCountDoesNotReachThreshold() {
        AtomicInteger callCount = new AtomicInteger();
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, 2, 2), countingModel(callCount), new StubPromptService());

        boolean summarized = memory.trySummarize("session-1", List.of(
                new MemoryMessage("Human", "first"),
                new MemoryMessage("AI", "second")
        ));

        assertThat(summarized).isFalse();
        assertThat(callCount).hasValue(0);
        assertThat(memory.getSummary("session-1")).isEmpty();
    }

    @Test
    void shouldSummarizeAndSaveSummaryWhenMessageCountExceedsThreshold() {
        AtomicInteger callCount = new AtomicInteger();
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, 2, 2), countingModel(callCount), new StubPromptService());

        boolean summarized = memory.trySummarize("session-1", List.of(
                new MemoryMessage("Human", "first"),
                new MemoryMessage("AI", "second"),
                new MemoryMessage("Human", "third")
        ));

        assertThat(summarized).isTrue();
        assertThat(callCount).hasValue(1);
        assertThat(memory.getSummary("session-1")).isEqualTo("conversation summary");
    }

    @Test
    void shouldReturnNonNegativeRetainedMessageCount() {
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, 2, -1), fixedSummaryModel("summary"), new StubPromptService());

        assertThat(memory.getRetainedMessageCount()).isZero();
    }

    private ChatModel fixedSummaryModel(String summary) {
        return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(summary))));
    }

    private ChatModel countingModel(AtomicInteger callCount) {
        return prompt -> {
            callCount.incrementAndGet();
            return fixedSummaryModel("conversation summary").call(prompt);
        };
    }
}
