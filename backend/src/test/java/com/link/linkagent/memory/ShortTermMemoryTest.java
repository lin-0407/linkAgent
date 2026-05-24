package com.link.linkagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermMemoryTest {

    @Test
    void shouldKeepRecentMessagesInAppendOrder() {
        ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());

        memory.append("session-1", "Human", "first");
        memory.append("session-1", "AI", "second");

        List<MemoryMessage> messages = memory.getRecentMessages("session-1");

        assertThat(messages)
                .extracting(MemoryMessage::content)
                .containsExactly("first", "second");
    }

    @Test
    void shouldDropOldestMessagesWhenWindowIsFull() {
        ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());

        for (int i = 1; i <= 12; i++) {
            memory.append("session-1", "Human", "message-" + i);
        }

        List<MemoryMessage> messages = memory.getRecentMessages("session-1");

        assertThat(messages).hasSize(10);
        assertThat(messages.getFirst().content()).isEqualTo("message-3");
        assertThat(messages.getLast().content()).isEqualTo("message-12");
    }

    @Test
    void shouldKeepOnlyConfiguredRecentMessages() {
        ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());

        memory.append("session-1", "Human", "first");
        memory.append("session-1", "AI", "second");
        memory.append("session-1", "Human", "third");

        memory.keepRecentMessages("session-1", 2);

        assertThat(memory.getRecentMessages("session-1"))
                .extracting(MemoryMessage::content)
                .containsExactly("second", "third");
    }

    @Test
    void shouldClearMessagesWhenRetainedCountIsNegative() {
        ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());

        memory.append("session-1", "Human", "first");
        memory.keepRecentMessages("session-1", -1);

        assertThat(memory.getRecentMessages("session-1")).isEmpty();
    }
}
