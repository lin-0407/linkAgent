package com.link.linkagent.memory;

import com.link.linkagent.memory.model.ConversationMessageRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

/**
 * 协调器测试：请求线程只负责「打分 → 扣预算 → 入队」，批量抽取与写入决策都由消费者线程执行（步骤 7）。
 */
class LongTermMemoryExtractionCoordinatorTest {

    private MemoryExtractionAccumulator accumulator;
    private TurnImportanceScorer scorer;
    private ConversationSessionMapper mapper;
    private LongTermMemoryExtractor extractor;
    private LongTermMemory longTermMemory;
    private LongTermMemoryDecisionMaker decisionMaker;
    private MemoryExtractionQueue queue;

    @BeforeEach
    void setUp() {
        accumulator = mock(MemoryExtractionAccumulator.class);
        scorer = mock(TurnImportanceScorer.class);
        mapper = mock(ConversationSessionMapper.class);
        extractor = mock(LongTermMemoryExtractor.class);
        longTermMemory = mock(LongTermMemory.class);
        decisionMaker = mock(LongTermMemoryDecisionMaker.class);
        queue = mock(MemoryExtractionQueue.class);
        doAnswer(call -> {
            when(accumulator.getCursor(call.getArgument(0))).thenReturn(call.getArgument(1));
            return null;
        }).when(accumulator).advanceCursor(anyString(), anyLong());
    }

    @Test
    void shouldSkipExtractionWhenBudgetNotReached() {
        when(scorer.score("问题", "回答")).thenReturn(20);
        when(accumulator.consume("default", 20)).thenReturn(MemoryExtractionAccumulator.Decision.WAIT);

        coordinator().afterTurn("default", "session-1", "问题", "回答", 12L);

        verifyNoInteractions(extractor);
        verifyNoInteractions(queue);
        verify(accumulator, never()).getCursor(anyString());
    }

    @Test
    void shouldEnqueueMessageRangeInsteadOfExtractingInRequestThread() {
        when(scorer.score("问题", "回答")).thenReturn(90);
        when(accumulator.consume("default", 90)).thenReturn(MemoryExtractionAccumulator.Decision.TRIGGER);
        when(accumulator.getCursor("default")).thenReturn(10L);
        when(queue.enqueue(any())).thenReturn(true);

        coordinator().afterTurn("default", "session-1", "问题", "回答", 12L);

        ArgumentCaptor<MemoryExtractionTask> captor = ArgumentCaptor.forClass(MemoryExtractionTask.class);
        verify(queue).enqueue(captor.capture());
        // 请求线程里一次模型调用都不该发生：入队的区间是「游标之后到本轮最后一条」（C6 / C7）
        assertThat(captor.getValue().fromMessageId()).isEqualTo(10L);
        assertThat(captor.getValue().toMessageId()).isEqualTo(12L);
        assertThat(captor.getValue().hasMessageRange()).isTrue();
        verifyNoInteractions(extractor);
    }

    @Test
    void shouldNotEnqueueWhenCursorAlreadyReachedLastMessage() {
        when(scorer.score("问题", "回答")).thenReturn(90);
        when(accumulator.consume("default", 90)).thenReturn(MemoryExtractionAccumulator.Decision.TRIGGER);
        when(accumulator.getCursor("default")).thenReturn(12L);

        coordinator().afterTurn("default", "session-1", "问题", "回答", 12L);

        // 游标已经到本轮最后一条消息：这批已经被压缩路径处理过，入队只会让消费者空跑
        verify(queue, never()).enqueue(any());
    }

    @Test
    void shouldSkipExtractionWhenAccumulatorIsDegraded() {
        when(scorer.score("问题", "回答")).thenReturn(90);
        when(accumulator.consume("default", 90)).thenReturn(MemoryExtractionAccumulator.Decision.DEGRADED);

        coordinator().afterTurn("default", "session-1", "问题", "回答", 12L);

        // Redis 不可用时不退回「在请求线程里抽一轮」：没有游标与队列既保证不了不重复，也要用户白等两次模型调用
        verifyNoInteractions(extractor, queue, longTermMemory);
    }

    @Test
    void shouldRunBothPhasesWhenConsumerHandlesRangeTask() {
        when(accumulator.getCursor("default")).thenReturn(10L);
        // 从最早未处理消息开始，才能保证游标推进时不会越过被 LIMIT 截掉的旧消息。
        when(mapper.listMessagesInRangeForUser("default", 10L, 12L, 200)).thenReturn(List.of(
                record(11L, "第一轮回答"),
                record(12L, "第一轮问题")
        ));
        when(extractor.extract(anyList(), any())).thenReturn(LongTermMemoryExtractionResult.remembered(
                new LongTermMemoryCandidate(true, "user.profile.summary", "用户是 Java 后端学习者", 90)));

        coordinator().processTask(MemoryExtractionTask.forMessageRange("default", "session-1", 10L, 12L, "now"));

        ArgumentCaptor<List<MemoryMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(extractor).extract(captor.capture(), any());
        assertThat(captor.getValue())
                .extracting(MemoryMessage::content)
                .containsExactly("第一轮回答", "第一轮问题");
        // 阶段一产出候选后交给阶段二写入决策，而不是直接落库（C12）
        verify(decisionMaker).decideAndApply(eq("default"), eq("session-1"), anyList());
        verify(accumulator).advanceCursor("default", 12L);
        verify(accumulator).resetBudget("default");
    }

    @Test
    void shouldRetryInPlaceAndLeaveCursorUntouchedWhenExtractionFails() {
        when(accumulator.getCursor("default")).thenReturn(10L);
        when(mapper.listMessagesInRangeForUser("default", 10L, 11L, 200))
                .thenReturn(List.of(record(11L, "第一轮回答")));
        when(extractor.extract(anyList(), any())).thenReturn(LongTermMemoryExtractionResult.failed());

        coordinator().processTask(MemoryExtractionTask.forMessageRange("default", "session-1", 10L, 11L, "now"));

        // 失败原地重试最多两次（首次 + 2 次重试），不把任务丢回队尾（C8）
        verify(extractor, times(3)).extract(anyList(), any());
        verify(accumulator, never()).advanceCursor(anyString(), anyLong());
        verify(accumulator, never()).resetBudget(anyString());
        verifyNoInteractions(decisionMaker);
    }

    @Test
    void shouldResetBudgetWhenTaskHasNoIncrementMessage() {
        when(accumulator.getCursor("default")).thenReturn(10L);
        when(mapper.listMessagesInRangeForUser("default", 10L, 11L, 200)).thenReturn(List.of());

        coordinator().processTask(MemoryExtractionTask.forMessageRange("default", "session-1", 10L, 11L, "now"));

        // 没有增量说明这批消息早已被压缩路径处理过：重置预算等下一轮，不用再花一次模型调用
        verifyNoInteractions(extractor);
        verify(accumulator).resetBudget("default");
    }

    @Test
    void shouldAdvanceCursorEvenWhenNoCandidateFound() {
        when(accumulator.getCursor("default")).thenReturn(10L);
        when(mapper.listMessagesInRangeForUser("default", 10L, 11L, 200))
                .thenReturn(List.of(record(11L, "今天天气不错")));
        when(extractor.extract(anyList(), any())).thenReturn(LongTermMemoryExtractionResult.noMemory());

        coordinator().processTask(MemoryExtractionTask.forMessageRange("default", "session-1", 10L, 11L, "now"));

        // 「没有值得记的」是成功：游标必须推进，否则同一批消息每轮都会被重抽
        verify(accumulator).advanceCursor("default", 11L);
        verify(accumulator).resetBudget("default");
        verifyNoInteractions(decisionMaker);
    }

    @Test
    void shouldProcessPreExtractedFactsWithoutTouchingCursor() {
        when(queue.enqueue(any())).thenReturn(true);
        boolean enqueued = coordinator().enqueueFacts("default", "session-1",
                List.of(new SummaryFactPoint("project.constraint.summary", "不要引入多租户", "原文", 90)));
        assertThat(enqueued).isTrue();

        coordinator().processTask(MemoryExtractionTask.forFacts("default", "session-1",
                List.of(new MemoryExtractionTask.MemoryExtractionTaskFact("project.constraint.summary", "不要引入多租户", 90)),
                "now"));

        // 预抽事实跳阶段一，但仍要过阶段二；游标由压缩路径推进过，这里不能再动（否则会把未抽取的消息推过去）
        verifyNoInteractions(extractor);
        verify(decisionMaker).decideAndApply(eq("default"), eq("session-1"), anyList());
        verify(accumulator, never()).advanceCursor(anyString(), anyLong());
        verify(accumulator, never()).resetBudget(anyString());
    }

    @Test
    void shouldAdvanceCursorWhenCompactionCoversNewMessages() {
        when(accumulator.getCursor("default")).thenReturn(10L);

        Long cursor = coordinator().advanceAfterCompaction("default", List.of(
                new MemoryMessage("Human", "第一轮问题", 60L),
                new MemoryMessage("AI", "第一轮回答", 80L)
        ));

        assertThat(cursor).isEqualTo(80L);
        verify(accumulator).advanceCursor("default", 80L);
    }

    @Test
    void shouldNotMoveCursorBackwardsAfterCompaction() {
        when(accumulator.getCursor("default")).thenReturn(100L);

        // 增量提取已经抽到 100，压缩涉及的 60/80 早就覆盖过，游标不能退回去重复抽取
        Long cursor = coordinator().advanceAfterCompaction("default", List.of(new MemoryMessage("Human", "旧问题", 60L)));

        assertThat(cursor).isEqualTo(100L);
        verify(accumulator, never()).advanceCursor(anyString(), anyLong());
    }

    @Test
    void shouldSkipCompactionAdvanceWhenMessagesHaveNoId() {
        Long cursor = coordinator().advanceAfterCompaction("default", List.of(new MemoryMessage("Human", "没落库的消息")));

        assertThat(cursor).isNull();
        verify(accumulator, never()).advanceCursor(anyString(), anyLong());
    }

    @Test
    void shouldFallBackToCurrentTurnWhenInfrastructureIsMissing() {
        // 最小构造（单测路径）：没有累加器、队列与决策器，退回每轮抽取本轮并直接落库
        LongTermMemoryExtractionCoordinator coordinator =
                new LongTermMemoryExtractionCoordinator(extractor, longTermMemory);
        when(extractor.extract(anyList())).thenReturn(LongTermMemoryExtractionResult.remembered(
                new LongTermMemoryCandidate(true, "user.profile.summary", "用户是 Java 后端学习者")));

        coordinator.afterTurn("default", "session-1", "问题", "回答");

        verify(extractor).extract(anyList());
        verify(longTermMemory).save("default", "user.profile.summary", "用户是 Java 后端学习者", "session-1", 0);
        verifyNoInteractions(accumulator);
        verifyNoInteractions(scorer);
    }

    @Test
    void shouldSkipEverythingWhenExtractorIsMissing() {
        LongTermMemoryExtractionCoordinator coordinator = new LongTermMemoryExtractionCoordinator(
                null, longTermMemory, decisionMaker, mapper, accumulator, scorer, queue, 200);

        coordinator.afterTurn("default", "session-1", "问题", "回答", 12L);

        verifyNoInteractions(scorer, accumulator, mapper, longTermMemory, queue);
    }

    @Test
    void shouldConsumeBoundaryWhenTaskRangeIsEmpty() {
        coordinator().processTask(MemoryExtractionTask.forMessageRange("default", "session-1", 5L, 5L, "now"));

        // 空区间任务（没有候选也没有消息）直接跳过，不读游标也不碰预算
        verifyNoInteractions(extractor, decisionMaker, longTermMemory);
        verify(accumulator, never()).getCursor(anyString());
    }

    @Test
    void shouldCompleteAllOldestBatchesBeforeFirstReadWithoutUsingBudget() {
        AtomicLong cursor = new AtomicLong(10);
        when(mapper.findHistoryBoundaryForNewSession("default", "new-session")).thenReturn(14L);
        when(accumulator.getCursor("default")).thenAnswer(call -> cursor.get());
        doAnswer(call -> { cursor.set(call.getArgument(1)); return null; })
                .when(accumulator).advanceCursor(eq("default"), anyLong());
        when(mapper.listMessagesInRangeForUser("default", 10, 14, 2)).thenReturn(List.of(record(11, "旧事件"), record(12, "旧回答")));
        when(mapper.listMessagesInRangeForUser("default", 12, 14, 2)).thenReturn(List.of(record(13, "后续事件"), record(14, "后续回答")));
        when(extractor.extract(anyList(), any())).thenReturn(LongTermMemoryExtractionResult.noMemory());
        LongTermMemoryExtractionCoordinator coordinator = new LongTermMemoryExtractionCoordinator(
                extractor, longTermMemory, decisionMaker, mapper, accumulator, scorer, queue, 2);

        coordinator.completeHistoryBeforeFirstRead("default", "new-session");

        assertThat(cursor.get()).isEqualTo(14);
        verify(extractor, times(2)).extract(anyList(), any());
        verifyNoInteractions(scorer);
        verify(accumulator, never()).consume(anyString(), anyInt());
        // 再次读取时游标已完成，旧任务不能重复写入，也不能清空后来积攒的预算。
        coordinator.completeHistoryBeforeFirstRead("default", "new-session");
        coordinator.processTask(MemoryExtractionTask.forMessageRange("default", "old", 10, 14, "now"));
        verify(extractor, times(2)).extract(anyList(), any());
        verify(accumulator, times(2)).resetBudget("default");
    }

    @Test
    void shouldFailFirstReadWithoutAdvancingCursorWhenExtractionFails() {
        when(mapper.findHistoryBoundaryForNewSession("default", "new-session")).thenReturn(12L);
        when(accumulator.getCursor("default")).thenReturn(10L);
        when(mapper.listMessagesInRangeForUser("default", 10, 12, 200)).thenReturn(List.of(record(11, "具体事件"), record(12, "回答")));
        when(extractor.extract(anyList(), any())).thenReturn(LongTermMemoryExtractionResult.failed());

        assertThatThrownBy(() -> coordinator().completeHistoryBeforeFirstRead("default", "new-session"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("旧会话记忆提取失败");
        verify(accumulator, never()).advanceCursor(anyString(), anyLong());
    }

    @Test
    void shouldSkipFirstReadCompletionForExistingOrEmptyHistorySession() {
        when(mapper.findHistoryBoundaryForNewSession("default", "existing-session")).thenReturn(0L);
        coordinator().completeHistoryBeforeFirstRead("default", "existing-session");
        verifyNoInteractions(extractor, accumulator, queue);
    }

    @Test
    void shouldFinishQueuedFactsEvenWhenHistoryCursorAlreadyReachedBoundary() {
        when(mapper.findHistoryBoundaryForNewSession("default", "new-session")).thenReturn(12L);
        when(accumulator.getCursor("default")).thenReturn(12L);
        when(queue.takePendingForUser("default", 12)).thenReturn(List.of(MemoryExtractionTask.forFacts("default", "old-session",
                List.of(new MemoryExtractionTask.MemoryExtractionTaskFact("user.event", "具体事件", 85)), "now")));
        coordinator().completeHistoryBeforeFirstRead("default", "new-session");
        verify(decisionMaker).decideAndApply(eq("default"), eq("old-session"), anyList());
        verifyNoInteractions(extractor);
    }

    @Test
    void shouldRestoreFailedAndUnprocessedQueuedFacts() {
        when(mapper.findHistoryBoundaryForNewSession("default", "new-session")).thenReturn(12L);
        var first = MemoryExtractionTask.forFacts("default", "old", List.of(new MemoryExtractionTask.MemoryExtractionTaskFact("user.event", "首个事件", 85)), "first");
        var second = MemoryExtractionTask.forFacts("default", "old", List.of(new MemoryExtractionTask.MemoryExtractionTaskFact("user.event", "后续事件", 85)), "second");
        when(queue.takePendingForUser("default", 12)).thenReturn(List.of(first, second));
        when(decisionMaker.decideAndApply(anyString(), anyString(), anyList())).thenThrow(new IllegalStateException("model unavailable"));

        assertThatThrownBy(() -> coordinator().completeHistoryBeforeFirstRead("default", "new-session"))
                .hasMessageContaining("旧会话记忆写入失败");
        verify(queue).enqueue(first);
        verify(queue).enqueue(second);
        verify(accumulator, never()).advanceCursor(anyString(), anyLong());
    }

    @Test
    void shouldWaitForDequeuedTaskBeforeReadingNewSession() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(accumulator.getCursor("default")).thenReturn(10L);
        when(mapper.findHistoryBoundaryForNewSession("default", "new-session")).thenReturn(12L);
        when(mapper.listMessagesInRangeForUser("default", 10, 12, 200)).thenReturn(List.of(record(11, "旧事件"), record(12, "回答")));
        when(queue.poll(1)).thenReturn(java.util.Optional.of(MemoryExtractionTask.forMessageRange("default", "old", 10, 12, "now")));
        when(extractor.extract(anyList(), any())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return LongTermMemoryExtractionResult.noMemory();
        });
        var coordinator = coordinator();
        var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var worker = threads.submit(() -> coordinator.consumeQueuedTask(queue, 1));
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var reader = threads.submit(() -> coordinator.completeHistoryBeforeFirstRead("default", "new-session"));
            assertThatThrownBy(() -> reader.get(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            verify(mapper, never()).findHistoryBoundaryForNewSession(anyString(), anyString());
            release.countDown();
            worker.get(3, java.util.concurrent.TimeUnit.SECONDS);
            reader.get(3, java.util.concurrent.TimeUnit.SECONDS);
            verify(extractor, times(1)).extract(anyList(), any());
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }

    private LongTermMemoryExtractionCoordinator coordinator() {
        return new LongTermMemoryExtractionCoordinator(extractor, longTermMemory, decisionMaker, mapper,
                accumulator, scorer, queue, 200);
    }

    private ConversationMessageRecord record(long id, String content) {
        ConversationMessageRecord record = new ConversationMessageRecord();
        record.setId(id);
        record.setSessionId("session-1");
        record.setRole(id % 2 == 0 ? "user" : "assistant");
        record.setContent(content);
        return record;
    }
}
