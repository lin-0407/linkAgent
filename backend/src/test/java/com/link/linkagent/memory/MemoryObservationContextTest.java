package com.link.linkagent.memory;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class MemoryObservationContextTest {
    @Test
    void timestampMetadataDoesNotChangeOriginalContentOrOrdinaryConversations() {
        MemoryMessage message = new MemoryMessage("assistant", "原始内容", 7L);
        assertEquals("", MemoryObservationContext.prefix(message));
        var snapshot = new MemoryObservationContext.Snapshot(Map.of(7L, new MemoryObservationContext.Location("source-session", "2023/04/10 (Mon) 17:50")), LocalDate.of(2023, 4, 11));
        try (var ignored = MemoryObservationContext.restore(snapshot)) {
            assertTrue(MemoryObservationContext.prefix(message).contains("2023/04/10 (Mon) 17:50"));
            assertEquals(LocalDate.of(2023, 4, 11), MemoryObservationContext.observationDate());
            assertEquals("原始内容", message.content());
        }
        assertNull(MemoryObservationContext.current());
        assertEquals("", MemoryObservationContext.prefix(message));
    }

    @Test
    void metadataIsRestoredInsideActualMemoryCallExecutor() throws Exception {
        var snapshot = new MemoryObservationContext.Snapshot(Map.of(), LocalDate.of(2023, 4, 11));
        try (var ignored = MemoryObservationContext.restore(snapshot)) {
            assertEquals(LocalDate.of(2023, 4, 11), MemoryCallTimeout.call("本地观察日期验证", MemoryObservationContext::observationDate));
            assertSame(snapshot, MemoryObservationContext.current());
        }
        assertNull(MemoryObservationContext.current());
    }

    @Test
    void cancelledFutureDoesNotPretendTheUnderlyingModelCallHasFinished() throws Exception {
        var snapshot = new MemoryObservationContext.Snapshot(Map.of(), LocalDate.of(2023, 4, 11));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var ignored = MemoryObservationContext.restore(snapshot)) {
            try {
                assertThrows(TimeoutException.class, () -> MemoryCallTimeout.call("本地超时收尾验证", 100, TimeUnit.MILLISECONDS, () -> {
                    entered.countDown();
                    try {
                        release.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return "finished";
                }));
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertFalse(snapshot.pendingCalls().awaitIdle(5));
            } finally {
                release.countDown();
            }
            assertTrue(snapshot.pendingCalls().awaitIdle(1000));
        }
    }
}
