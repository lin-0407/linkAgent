package com.link.linkagent.core;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.TokenCounter;
import com.link.linkagent.memory.InMemoryShortTermMemoryStore;
import com.link.linkagent.memory.LongTermMemory;
import com.link.linkagent.memory.LongTermMemoryExtractionCoordinator;
import com.link.linkagent.memory.MemoryMessage;
import com.link.linkagent.memory.ShortTermMemory;
import com.link.linkagent.memory.SummaryMemory;
import com.link.linkagent.prompt.StubPromptService;
import com.link.linkagent.tool.ToolExecutionProperties;
import com.link.linkagent.tool.ToolExecutor;
import com.link.linkagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 压缩后「按 token 预算保留尾部」的回归测试。
 *
 * <p>锁定 {@code AgentExecutor#retainedMessageCountForTail} 的行为：
 * <ol>
 *   <li>从最后一条消息往前累计，按整条消息计量，超预算就停在当前位置，不切任何一条消息；</li>
 *   <li>最后一条消息自己就超过预算时也至少保留 1 条，避免压缩后一条历史都不剩；</li>
 *   <li>token 预算为 0 时退回 {@code retained-message-count} 旧口径；</li>
 *   <li>拿不到 TokenCounter 时完全不判断压缩，行为和改造前一致。</li>
 * </ol>
 *
 * <p>用 mock 的 TokenCounter 按消息正文给每条消息指定 token 数，上下文整体的 token 数固定给一个大值，
 * 保证一定进入压缩分支；这样保留条数完全由「尾部预算累计」决定，和真实词表无关。
 */
class AgentExecutorRetainedTokenBudgetTest {

    private static final String SESSION_ID = "budget-session";
    private static final String USER_ID = "budget-user";
    private static final int CONTEXT_TOKENS = 100_000;
    private static final int THRESHOLD = 100;

    @Test
    void shouldRetainWholeMessagesFromTailUntilTokenBudgetExceeded() {
        // 消息 token：[10, 20, 30, 40, 50]，预算 130：尾部 m5(50)+m4(40)+m3(30)=120 可留，再加 m2(20) 就 140 超预算
        Harness harness = harness(130, 1, tokenCounter(Map.of(
                "m1", 10, "m2", 20, "m3", 30, "m4", 40, "m5", 50)));

        AgentExecutor.PreparedMemoryContext context = harness.executor().prepareMemoryContext(
                SESSION_ID, USER_ID, "问题");

        assertThat(context.compression()).isNotNull();
        assertThat(context.compression().get("compressed_message_count")).isEqualTo(2);
        // 按整条消息保留：留下的就是 m3/m4/m5 原文，不会出现半条
        assertThat(harness.memory().getRecentMessages(SESSION_ID))
                .extracting(MemoryMessage::content).containsExactly("m3", "m4", "m5");
        ArgumentCaptor<List<MemoryMessage>> compressed = captureSummarizedMessages(harness.summary());
        assertThat(compressed.getValue()).extracting(MemoryMessage::content)
                .containsExactly("m1", "m2");
    }

    @Test
    void shouldKeepAtLeastOneMessageWhenLastMessageAloneExceedsBudget() {
        // 最后一条 500 token 自己就超过预算 130：仍然至少保留 1 条，否则压缩后没有历史上下文
        Harness harness = harness(130, 2, tokenCounter(Map.of(
                "m1", 10, "m2", 20, "m3", 30, "m4", 40, "m5", 500)));

        AgentExecutor.PreparedMemoryContext context = harness.executor().prepareMemoryContext(
                SESSION_ID, USER_ID, "问题");

        assertThat(context.compression()).isNotNull();
        assertThat(context.compression().get("compressed_message_count")).isEqualTo(4);
        assertThat(harness.memory().getRecentMessages(SESSION_ID))
                .extracting(MemoryMessage::content).containsExactly("m5");
        assertThat(captureSummarizedMessages(harness.summary()).getValue())
                .extracting(MemoryMessage::content).containsExactly("m1", "m2", "m3", "m4");
    }

    @Test
    void shouldFallBackToRetainedMessageCountWhenTokenBudgetIsZero() {
        // 预算配置为 0：不按 token 累计，退回 retained-message-count=2
        Harness harness = harness(0, 2, tokenCounter(Map.of(
                "m1", 10, "m2", 20, "m3", 30, "m4", 40, "m5", 50)));

        AgentExecutor.PreparedMemoryContext context = harness.executor().prepareMemoryContext(
                SESSION_ID, USER_ID, "问题");

        assertThat(context.compression()).isNotNull();
        assertThat(context.compression().get("compressed_message_count")).isEqualTo(3);
        assertThat(harness.memory().getRecentMessages(SESSION_ID))
                .extracting(MemoryMessage::content).containsExactly("m4", "m5");
    }

    @Test
    void shouldMeasureToolCallByRenderedTextAndKeepPairAtTrimBoundary() {
        SummaryMemory summary = mock(SummaryMemory.class);
        when(summary.getTokenThreshold()).thenReturn(THRESHOLD);
        when(summary.getRetainedTokenBudget()).thenReturn(250);
        when(summary.getRetainedMessageCount()).thenReturn(1);
        when(summary.getSummary(anyString())).thenReturn("");
        when(summary.summarize(anyString(), any(), any())).thenReturn("压缩摘要");
        // 空串的 token 数故意给大值：如果实现错拿 content 计量调用消息（content 为空），保留边界会不同
        TokenCounter counter = tokenCounter(Map.of(
                "问题", 100,
                "ToolCall: echo(参数)", 100,
                "结果", 100,
                "最终回答", 100,
                "", 999));
        Harness harness = harness(summary, counter, List.of(
                new MemoryMessage("Human", "问题", 1L),
                MemoryMessage.toolCall("AI", 2L, "call_1", "echo", "参数"),
                MemoryMessage.toolResult(ShortTermMemory.ROLE_TOOL, 3L, "call_1", "echo", "结果"),
                new MemoryMessage("AI", "最终回答", 4L)));

        AgentExecutor.PreparedMemoryContext context = harness.executor().prepareMemoryContext(
                SESSION_ID, USER_ID, "问题");

        assertThat(context.compression()).isNotNull();
        // 尾部累计：最终回答 100 + 结果 100 = 200 < 250，再加调用渲染文本 100 就超预算 → 原始保留 2 条；
        // 裁剪边界正好落在 tool 结果上，前一条 assistant 调用必须一起留下 → 实际保留 3 条、只压缩最早的问题。
        assertThat(context.compression().get("compressed_message_count")).isEqualTo(1);
        List<MemoryMessage> retained = harness.memory().getRecentMessages(SESSION_ID);
        assertThat(retained).extracting(MemoryMessage::role).containsExactly("AI", "Tool", "AI");
        assertThat(retained.get(0).isToolCall()).isTrue();
        assertThat(retained.get(1).isToolResult()).isTrue();
        assertThat(retained.get(1).toolCallId()).isEqualTo(retained.get(0).toolCallId());
        assertThat(captureSummarizedMessages(summary).getValue())
                .extracting(MemoryMessage::content).containsExactly("问题");
        // 计量口径是渲染后的 promptText（ToolCall: 工具(参数)），不是工具调用消息的空 content
        verify(counter, atLeastOnce()).count("ToolCall: echo(参数)");
        verify(counter, never()).count("");
    }

    @Test
    void shouldSkipCompressionWhenTokenCounterIsMissing() {
        // 单测/最小构造没有计量器时不做压缩判断，也不能去调摘要模型
        Harness harness = harnessWithoutTokenCounter();

        AgentExecutor.PreparedMemoryContext context = harness.executor().prepareMemoryContext(
                SESSION_ID, USER_ID, "问题");

        assertThat(context.compression()).isNull();
        assertThat(harness.memory().getRecentMessages(SESSION_ID)).hasSize(5);
        assertThat(context.text())
                .contains("Recent conversation:")
                .contains("Human:m5");
        verify(harness.summary(), never()).summarize(anyString(), any(), any());
    }

    @Test
    void shouldSkipRetryUntilContextGrowsAfterCompressionFailure() {
        // 压缩失败后原文仍在；如果下一轮只多一点点 token 就重试，会在阈值附近连续烧生成/复核。
        SummaryMemory summary = mock(SummaryMemory.class);
        when(summary.getTokenThreshold()).thenReturn(THRESHOLD);
        when(summary.getRetainedTokenBudget()).thenReturn(250);
        when(summary.getRetainedMessageCount()).thenReturn(1);
        when(summary.getSummary(anyString())).thenReturn("");
        when(summary.summarize(anyString(), any(), any())).thenReturn(null);
        when(summary.getRetryTokenDelta()).thenReturn(16_000);
        Harness harness = harness(summary, tokenCounter(Map.of()));

        harness.executor().prepareMemoryContext(SESSION_ID, USER_ID, "问题");
        harness.executor().prepareMemoryContext(SESSION_ID, USER_ID, "问题");

        // 上下文 token 没增长（仍是 100k），第二次直接跳过压缩，不再打模型。
        verify(summary, times(1)).summarize(anyString(), any(), any());
    }

    private Harness harness(int retainedTokenBudget, int retainedMessageCount, TokenCounter tokenCounter) {
        SummaryMemory summary = mock(SummaryMemory.class);
        when(summary.getTokenThreshold()).thenReturn(THRESHOLD);
        when(summary.getRetainedTokenBudget()).thenReturn(retainedTokenBudget);
        when(summary.getRetainedMessageCount()).thenReturn(retainedMessageCount);
        when(summary.getSummary(anyString())).thenReturn("");
        when(summary.summarize(anyString(), any(), any())).thenReturn("压缩摘要");
        return harness(summary, tokenCounter);
    }

    private Harness harnessWithoutTokenCounter() {
        SummaryMemory summary = mock(SummaryMemory.class);
        when(summary.getTokenThreshold()).thenReturn(THRESHOLD);
        return harness(summary, null);
    }

    private Harness harness(SummaryMemory summary, TokenCounter tokenCounter) {
        List<MemoryMessage> messages = new java.util.ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            messages.add(new MemoryMessage(index % 2 == 1 ? "Human" : "AI", "m" + index));
        }
        return harness(summary, tokenCounter, messages);
    }

    private Harness harness(SummaryMemory summary, TokenCounter tokenCounter, List<MemoryMessage> messages) {
        ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());
        messages.forEach(message -> memory.append(SESSION_ID, message));
        LongTermMemory longTerm = mock(LongTermMemory.class);
        when(longTerm.retrieveForContext(anyString(), anyString())).thenReturn(List.of());
        LongTermMemoryExtractionCoordinator coordinator = mock(LongTermMemoryExtractionCoordinator.class);
        // 单测模拟「没有游标」：Mockito 对 Long 返回值默认给 0，会把所有带 id 的消息都当成未抽取而阻止裁剪
        when(coordinator.advanceAfterCompaction(anyString(), any())).thenReturn(null);
        ToolRegistry registry = new ToolRegistry(List.of());
        AgentExecutor executor = new AgentExecutor(
                mock(LLMService.class), registry,
                new ToolExecutor(registry, new ToolExecutionProperties(10, 0)),
                memory, summary, longTerm, null, new StubPromptService(),
                null, null, null, null, null, null,
                tokenCounter, null, null, coordinator);
        return new Harness(executor, memory, summary);
    }

    /** 按消息正文指定 token 数；上下文整体文本不在映射里时返回固定大值，保证触发压缩。 */
    private TokenCounter tokenCounter(Map<String, Integer> tokensByContent) {
        TokenCounter counter = mock(TokenCounter.class);
        Map<String, Integer> counts = new LinkedHashMap<>(tokensByContent);
        when(counter.count(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            Integer exact = counts.get(text);
            return exact == null ? CONTEXT_TOKENS : exact;
        });
        return counter;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<MemoryMessage>> captureSummarizedMessages(SummaryMemory summary) {
        ArgumentCaptor<List<MemoryMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(summary).summarize(eq(SESSION_ID), captor.capture(), any());
        return captor;
    }

    private record Harness(AgentExecutor executor, ShortTermMemory memory, SummaryMemory summary) {
    }
}
