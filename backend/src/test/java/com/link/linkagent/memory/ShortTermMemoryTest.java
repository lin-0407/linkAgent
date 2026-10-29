package com.link.linkagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermMemoryTest {

    @Test
    void snapshotCompactionPreservesConcurrentAppendsAndReportsOnlyTrimmedBoundary() throws Exception {
        ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());
        memory.append("compaction-race", "Human", "已摘要的问题", 1L);
        memory.append("compaction-race", "AI", "保留的回答", 2L);
        var snapshot = memory.getRecentMessages("compaction-race");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var appended = pool.submit(() -> {
                start.await();
                for (long id = 3; id <= 1002; id++) memory.append("compaction-race", "Human", "新消息" + id, id);
                return null;
            });
            start.countDown();
            assertThat(memory.keepRecentMessages("compaction-race", snapshot, 1, null)).isEqualTo(1);
            appended.get(5, TimeUnit.SECONDS);
        }
        assertThat(memory.getRecentMessages("compaction-race")).hasSize(1001)
                .extracting(MemoryMessage::messageId).containsExactlyElementsOf(
                        java.util.stream.LongStream.rangeClosed(2, 1002).boxed().toList());
    }

    @Test
    void snapshotCompactionDeletesOnlySnapshotOccurrencesOfIdenticalTemporaryMessages() {
        ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());
        memory.append("temporary", "Human", "重复临时消息");
        var snapshot = memory.getRecentMessages("temporary");
        memory.append("temporary", "Human", "重复临时消息");
        assertThat(memory.keepRecentMessages("temporary", snapshot, 0, null)).isZero();
        assertThat(memory.getRecentMessages("temporary")).singleElement()
                .extracting(MemoryMessage::content).isEqualTo("重复临时消息");
    }

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

    @Test
    void shouldKeepMessagesNotCoveredByCursorEvenWhenCountAllowsTrimming() {
        ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());
        for (long id = 1; id <= 5; id++) {
            memory.append("session-1", "Human", "message-" + id, id);
        }

        // 游标只走到 3：4、5 还没进长期记忆，即使条数上只剩下 1 条也不能裁
        memory.keepRecentMessages("session-1", 1, 3L);

        assertThat(memory.getRecentMessages("session-1"))
                .extracting(MemoryMessage::content)
                .containsExactly("message-4", "message-5");
    }

    @Test
    void shouldNotTrimAnythingWhenCursorIsBehindAllMessages() {
        ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());
        memory.append("session-1", "Human", "old", 10L);
        memory.append("session-1", "AI", "new", 11L);

        // 游标 5 比所有消息都小：一条都不算「已抽取」，全保留
        memory.keepRecentMessages("session-1", 1, 5L);

        assertThat(memory.getRecentMessages("session-1"))
                .extracting(MemoryMessage::content)
                .containsExactly("old", "new");
    }

    @Test
    void shouldTreatMessageWithoutIdAsCovered() {
        ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());
        memory.append("session-1", "Human", "no-id");
        memory.append("session-1", "AI", "persisted", 30L);

        // 没有消息 id 的消息在消息表里查不到，永远不会被提取，按已覆盖处理，不阻塞裁剪
        memory.keepRecentMessages("session-1", 1, 100L);

        assertThat(memory.getRecentMessages("session-1"))
                .extracting(MemoryMessage::content)
                .containsExactly("persisted");
    }
}
