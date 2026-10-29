package com.link.linkagent.core;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.LlmCallResult;
import com.link.linkagent.llm.TokenCounter;
import com.link.linkagent.memory.ConversationSessionMapper;
import com.link.linkagent.memory.InMemoryShortTermMemoryStore;
import com.link.linkagent.memory.LongTermMemory;
import com.link.linkagent.memory.LongTermMemoryExtractionCoordinator;
import com.link.linkagent.memory.ShortTermMemory;
import com.link.linkagent.memory.SummaryMemory;
import com.link.linkagent.memory.model.ConversationMessageRecord;
import com.link.linkagent.memory.model.ConversationSessionRecord;
import com.link.linkagent.prompt.StubPromptService;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.tool.ToolExecutionProperties;
import com.link.linkagent.tool.ToolExecutor;
import com.link.linkagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentExecutorPersistenceRecoveryTest {
    @Test
    void resumedAgentActuallyReceivesRestoredTailAcrossFollowingTurns() {
        Harness harness = harness(false);
        harness.executor.run("session", "default", "继续分析", AgentExecutionMode.REACT);
        harness.executor.run("session", "default", "再补一点", AgentExecutionMode.REACT);

        assertThat(harness.llm.requests).hasSize(2).allSatisfy(request -> {
            assertThat(request).contains("压缩后的摘要", "仍应保留的问题", "仍应保留的回答");
            assertThat(request).doesNotContain("已压缩的原文");
        });
        assertThat(harness.llm.requests.get(1)).contains("继续分析", "本轮回答");
    }

    @Test
    void legacyCheckpointRecoveryDoesNotPutOldSummaryNextToCompleteOriginals() {
        Harness harness = harness(true);
        harness.executor.run("session", "default", "继续分析", AgentExecutionMode.REACT);
        assertThat(harness.llm.requests).singleElement().satisfies(request -> {
            assertThat(request).contains("已压缩的原文", "仍应保留的问题", "仍应保留的回答");
            assertThat(request).doesNotContain("压缩后的摘要");
        });
    }

    @Test
    void ownerMismatchCannotReassignSessionOrCallModel() {
        Harness harness = harness(false);
        assertThatThrownBy(() -> harness.executor.run("session", "other-user", "继续分析", AgentExecutionMode.REACT))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(harness.llm.requests).isEmpty();
        verify(harness.mapper, never()).upsertSession(any());
    }

    @Test
    void legacyWarmCacheCompactionRetainsTailFromRecoveredSnapshot() {
        Harness harness = harness(true);
        harness.memory.append("session", "AI", "仍应保留的回答", 4L);
        when(harness.summary.getTokenThreshold()).thenReturn(100);
        when(harness.summary.getRetainedMessageCount()).thenReturn(2);
        when(harness.summary.summarize(eq("session"), any(), any(), eq(""))).thenReturn("新的摘要");
        TokenCounter counter = mock(TokenCounter.class);
        when(counter.count(anyString())).thenReturn(1000);
        ToolRegistry registry = new ToolRegistry(List.of());
        AgentExecutor executor = new AgentExecutor(harness.llm, registry,
                new ToolExecutor(registry, new ToolExecutionProperties(10, 0)), harness.memory, harness.summary,
                mock(LongTermMemory.class), null, new StubPromptService(), null, null, null, null, null,
                harness.mapper, counter, null, null, mock(LongTermMemoryExtractionCoordinator.class));

        AgentExecutor.PreparedMemoryContext context = executor.prepareSummaryContext("session", "default", "继续");

        assertThat(context.text()).contains("新的摘要", "仍应保留的问题", "仍应保留的回答");
        assertThat(harness.memory.getRecentMessages("session")).extracting(message -> message.messageId())
                .containsExactly(3L, 4L);
        verify(harness.summary).recordCompactionCheckpoint("session", "default", "新的摘要", 2, 1000, 1000, 2L);
    }

    private Harness harness(boolean legacy) {
        CapturingLlm llm = new CapturingLlm();
        ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());
        ConversationSessionMapper mapper = mock(ConversationSessionMapper.class);
        ConversationSessionRecord session = new ConversationSessionRecord();
        session.setSessionId("session"); session.setUserId("default");
        when(mapper.findSessionById("session")).thenReturn(Optional.of(session));
        ConversationMessageRecord summary = message(5, "summary", "压缩后的摘要");
        if (!legacy) summary.setToolArguments("{\"compressedThroughMessageId\":2}");
        when(mapper.findLatestSummary("session")).thenReturn(Optional.of(summary));
        when(mapper.listMessagesBySession("session")).thenReturn(List.of(
                message(1, "user", "已压缩的原文"), message(2, "assistant", "已压缩的原文回答"),
                message(3, "user", "仍应保留的问题"), message(4, "assistant", "仍应保留的回答"), summary));
        AtomicLong ids = new AtomicLong(5);
        doAnswer(invocation -> {
            ((ConversationMessageRecord) invocation.getArgument(0)).setId(ids.incrementAndGet());
            return 1;
        }).when(mapper).insertMessage(any());
        SummaryMemory summaries = mock(SummaryMemory.class);
        when(summaries.getSummary("session")).thenReturn("压缩后的摘要");
        LongTermMemory longTerm = mock(LongTermMemory.class);
        when(longTerm.retrieveForContext(anyString(), anyString())).thenReturn(List.of());
        ToolRegistry registry = new ToolRegistry(List.of());
        RuntimeSettingService settings = mock(RuntimeSettingService.class);
        AgentExecutor executor = new AgentExecutor(llm, registry,
                new ToolExecutor(registry, new ToolExecutionProperties(10, 0)), memory, summaries, longTerm,
                null, new StubPromptService(), settings, null, null, null, null, mapper, null, null, null,
                mock(LongTermMemoryExtractionCoordinator.class));
        return new Harness(executor, llm, mapper, memory, summaries);
    }

    private ConversationMessageRecord message(long id, String role, String content) {
        ConversationMessageRecord message = new ConversationMessageRecord();
        message.setId(id); message.setSessionId("session"); message.setUserId("default");
        message.setRole(role); message.setContent(content);
        return message;
    }

    private record Harness(AgentExecutor executor, CapturingLlm llm, ConversationSessionMapper mapper,
                           ShortTermMemory memory, SummaryMemory summary) {}

    private static class CapturingLlm extends LLMService {
        private final List<String> requests = new ArrayList<>();

        @Override
        public LlmCallResult chatWithUsage(String systemPrompt, String userMessage) {
            requests.add(userMessage);
            return new LlmCallResult("Final Answer:本轮回答", "test", 1, 1, 2, 1L);
        }
    }
}
