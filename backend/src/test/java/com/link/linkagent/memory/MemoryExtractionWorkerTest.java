package com.link.linkagent.memory;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doNothing;

/**
 * 消费者线程测试：只验证「取到任务交给协调器」与「异常不把线程带走」两件事。
 * 线程本身的启停由 Spring 生命周期管理，不在这里断言，避免测试变成时序赌博。
 */
class MemoryExtractionWorkerTest {

    @Test
    void shouldDelegatePolledTaskToCoordinator() {
        MemoryExtractionQueue queue = mock(MemoryExtractionQueue.class);
        LongTermMemoryExtractionCoordinator coordinator = spy(new LongTermMemoryExtractionCoordinator(null, null));
        doNothing().when(coordinator).processTask(any());
        MemoryExtractionTask task = MemoryExtractionTask.forMessageRange("default", "session-1", 1L, 2L, "now");
        when(queue.poll(anyInt())).thenReturn(Optional.of(task));

        MemoryExtractionWorker worker = new MemoryExtractionWorker(queue, coordinator);

        assertThat(worker.pollAndProcessOnce()).isTrue();
        verify(coordinator).processTask(task);
    }

    @Test
    void shouldKeepLoopAliveWhenCoordinatorThrows() {
        MemoryExtractionQueue queue = mock(MemoryExtractionQueue.class);
        LongTermMemoryExtractionCoordinator coordinator = spy(new LongTermMemoryExtractionCoordinator(null, null));
        when(queue.poll(anyInt())).thenReturn(Optional.of(
                MemoryExtractionTask.forMessageRange("default", "session-1", 1L, 2L, "now")));
        doThrow(new IllegalStateException("boom")).when(coordinator).processTask(any());

        MemoryExtractionWorker worker = new MemoryExtractionWorker(queue, coordinator);

        // 异常在循环内收口：一个任务处理不了不能把整条链路停掉
        assertThat(worker.pollAndProcessOnce()).isFalse();
        assertThat(worker.isRunning()).isFalse();
    }
}
