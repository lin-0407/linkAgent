package com.link.linkagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryShortTermMemoryStoreTest {

    @Test
    void shouldTrimMessagesByConfiguredWindowSize() {
        InMemoryShortTermMemoryStore store = new InMemoryShortTermMemoryStore();

        store.append("session-1", new MemoryMessage("Human", "first"), 2);
        store.append("session-1", new MemoryMessage("AI", "second"), 2);
        store.append("session-1", new MemoryMessage("Human", "third"), 2);

        List<MemoryMessage> messages = store.getRecentMessages("session-1");

        assertThat(messages)
                .extracting(MemoryMessage::content)
                .containsExactly("second", "third");
    }

    @Test
    void shouldReplaceMessages() {
        InMemoryShortTermMemoryStore store = new InMemoryShortTermMemoryStore();

        store.append("session-1", new MemoryMessage("Human", "first"), 10);
        store.replaceMessages("session-1", List.of(new MemoryMessage("AI", "summary tail")));

        assertThat(store.getRecentMessages("session-1"))
                .containsExactly(new MemoryMessage("AI", "summary tail"));
    }
}
