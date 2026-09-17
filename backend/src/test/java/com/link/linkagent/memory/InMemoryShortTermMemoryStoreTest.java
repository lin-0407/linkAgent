package com.link.linkagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryShortTermMemoryStoreTest {

    @Test
    void shouldAppendMessagesWithoutWindowTrimming() {
        InMemoryShortTermMemoryStore store = new InMemoryShortTermMemoryStore();

        store.append("session-1", new MemoryMessage("Human", "first"));
        store.append("session-1", new MemoryMessage("AI", "second"));
        store.append("session-1", new MemoryMessage("Human", "third"));

        List<MemoryMessage> messages = store.getRecentMessages("session-1");

        // 按条数裁剪已删除：压缩时机由上层按 token 判断，存储层只负责追加。
        assertThat(messages)
                .extracting(MemoryMessage::content)
                .containsExactly("first", "second", "third");
    }

    @Test
    void shouldReplaceMessages() {
        InMemoryShortTermMemoryStore store = new InMemoryShortTermMemoryStore();

        store.append("session-1", new MemoryMessage("Human", "first"));
        store.replaceMessages("session-1", List.of(new MemoryMessage("AI", "summary tail")));

        assertThat(store.getRecentMessages("session-1"))
                .containsExactly(new MemoryMessage("AI", "summary tail"));
    }
}
