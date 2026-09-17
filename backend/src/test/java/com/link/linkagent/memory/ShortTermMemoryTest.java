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
    void shouldKeepAllMessagesBecauseWindowIsRemoved() {
        ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());

        for (int i = 1; i <= 12; i++) {
            memory.append("session-1", "Human", "message-" + i);
        }

        List<MemoryMessage> messages = memory.getRecentMessages("session-1");

        // 10 条滑动窗口已删除：是否压缩由请求 token 数决定，不再按条数丢消息。
        assertThat(messages).hasSize(12);
        assertThat(messages.getFirst().content()).isEqualTo("message-1");
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
