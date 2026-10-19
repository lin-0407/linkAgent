package com.link.linkagent.core;

import com.link.linkagent.llm.ChatReplayContext;
import com.link.linkagent.llm.ChatRequestEnvelope;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.LlmCallResult;
import com.link.linkagent.llm.TokenCounter;
import com.link.linkagent.memory.InMemoryShortTermMemoryStore;
import com.link.linkagent.memory.LongTermMemory;
import com.link.linkagent.memory.LongTermMemoryExtractionCoordinator;
import com.link.linkagent.memory.LongTermMemoryRecord;
import com.link.linkagent.memory.ShortTermMemory;
import com.link.linkagent.memory.SummaryMemory;
import com.link.linkagent.memory.SummaryMemoryProperties;
import com.link.linkagent.prompt.StubPromptService;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.tool.ToolExecutionProperties;
import com.link.linkagent.tool.ToolExecutor;
import com.link.linkagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证摘要压缩调用真的重放了上一轮真实请求的信封（2026-10 DeepSeek 缓存命中优化）。
 *
 * <p>只断言「结构」不调真实模型：真实请求用假的 LLMService 记录，摘要重放用另一个假 LLMService 收口，
 * 检查重放消息是不是上一轮真实请求文本的前缀、信封是不是同一条请求形状。
 * 供应商侧的真实命中率由评测平台/低阈值联调验证，不在单测里调外网。
 */
class AgentExecutorCacheReplayTest {

    private static final String SESSION = "cache-replay-session";
    private static final int THRESHOLD = 30;
    private static final int RETAINED_BUDGET = 25;

    @Test
    void shouldReplayLastRealRequestEnvelopeWhenCompressing() {
        CapturingLlmService llm = new CapturingLlmService();
        AtomicReference<ChatRequestEnvelope> replayedEnvelope = new AtomicReference<>();
        AtomicReference<List<Message>> replayedMessages = new AtomicReference<>();
        LLMService replayLlm = new LLMService() {
            @Override
            public LlmCallResult replayChat(ChatRequestEnvelope envelope, List<Message> messages, Integer maxOutputTokens) {
                replayedEnvelope.set(envelope);
                replayedMessages.set(messages);
                return new LlmCallResult("压缩摘要", "test-model", 10, 5, 15, 1L);
            }
        };
        ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());
        SummaryMemory summary = new SummaryMemory(
                new SummaryMemoryProperties(true, THRESHOLD, 2, RETAINED_BUDGET, 80),
                prompt -> {
                    throw new IllegalStateException("生产路径应通过 LLMService 重放，不应直连摘要模型");
                },
                new StubPromptService(), null, null, null, null, null, replayLlm);
        LongTermMemory longTerm = mock(LongTermMemory.class);
        LongTermMemoryRecord memoryRecord = new LongTermMemoryRecord();
        memoryRecord.setId(1L);
        memoryRecord.setUserId("default");
        memoryRecord.setMemoryKey("user.preference.language");
        memoryRecord.setContent("用户偏好使用 Java");
        memoryRecord.setImportance(90);
        when(longTerm.retrieveForContext(anyString(), anyString())).thenReturn(List.of(memoryRecord));
        LongTermMemoryExtractionCoordinator coordinator = mock(LongTermMemoryExtractionCoordinator.class);
        when(coordinator.advanceAfterCompaction(anyString(), any())).thenReturn(null);
        // 关掉结构化内核，走文本路径，测试只关心上下文组装与摘要重放
        RuntimeSettingService settings = mock(RuntimeSettingService.class);
        when(settings.isStructuredKernelEnabled()).thenReturn(false);

        AgentExecutor executor = new AgentExecutor(
                llm, new ToolRegistry(List.of()),
                new ToolExecutor(new ToolRegistry(List.of()), new ToolExecutionProperties(10, 0)),
                memory, summary, longTerm, null, new StubPromptService(),
                settings, null, null, null, null, null,
                tokenCounter(), null, null, coordinator);

        // 预置几条旧消息：上一轮真实请求的上下文里必须已经有 Recent conversation 段，
        // 否则被压缩消息不在上一轮请求里，前缀本来就无处可复用。
        memory.append(SESSION, "Human", "旧消息一");
        memory.append(SESSION, "AI", "旧回答一");
        memory.append(SESSION, "Human", "旧消息二");
        memory.append(SESSION, "AI", "旧回答二");
        memory.append(SESSION, "Human", "旧消息三");
        executor.run(SESSION, "default", "第一轮问题");
        String firstRequest = llm.lastUserMessage;
        String firstSystemPrompt = llm.lastSystemPrompt;
        assertThat(firstRequest).contains("Recent conversation:");

        // 再跑一轮就会过阈值：run() 已经把第一轮的 Human/AI 写回短期记忆，
        // 此时被压缩的是上一轮请求里已经存在的那批旧消息。
        executor.run(SESSION, "default", "第二轮问题");

        List<Message> messages = replayedMessages.get();
        assertThat(messages).as("摘要生成应该走信封重放").isNotNull().hasSize(2);
        // 信封必须是上一轮真实请求的形状：同一个出口、同一份 system、同一条请求消息。
        ChatRequestEnvelope envelope = replayedEnvelope.get();
        assertThat(envelope).isNotNull();
        assertThat(envelope.strictTransport()).isFalse();
        assertThat(envelope.systemPrompt()).isEqualTo(firstSystemPrompt);
        // 第一条消息 = 上一轮真实请求的完整消息（逐字节相同）；第二条 = 追加的压缩指令。
        String replayedRequest = messages.get(0).getText();
        String instruction = messages.get(1).getText();
        assertThat(replayedRequest).isEqualTo(firstRequest);
        assertThat(instruction).contains("[test-prompt:summary_memory.system]");
        // 拼接顺序：摘要/短期记忆在前，长期记忆在后；长期记忆在最前面会让每轮前缀都变
        assertThat(firstRequest.indexOf("Recent conversation:"))
                .isLessThan(firstRequest.indexOf("Long-term memory:"));
        assertThat(memory.getRecentMessages(SESSION)).hasSizeLessThan(7);
        assertThat(summary.getSummary(SESSION)).isEqualTo("压缩摘要");
    }

    /** 上一轮真实请求的记录器：只记 system 和对话文本，并把真实请求信封写进当轮作用域。 */
    private static class CapturingLlmService extends LLMService {

        private String lastSystemPrompt;
        private String lastUserMessage;

        CapturingLlmService() {
            super();
        }

        @Override
        public String chat(String systemPrompt, String userMessage) {
            this.lastSystemPrompt = systemPrompt;
            this.lastUserMessage = userMessage;
            return """
                    Thought:信息已经够了
                    Final Answer:好的
                    """;
        }

        @Override
        public LlmCallResult chatWithUsage(String systemPrompt, String userMessage) {
            String answer = chat(systemPrompt, userMessage);
            ChatReplayContext.record(ChatRequestEnvelope.main(systemPrompt,
                    List.of(new UserMessage(userMessage)), null));
            return new LlmCallResult(answer, "test-model", 3, 5, 8, 1L);
        }
    }

    /**
     * 第一轮上下文按 20 token 算（低于阈值），第二轮提问时按 1000 算（触发压缩）；
     * 单条消息正文固定 10，用于保留尾部预算的累计。
     */
    private static TokenCounter tokenCounter() {
        TokenCounter counter = mock(TokenCounter.class);
        when(counter.count(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            if (text == null) {
                return 10;
            }
            if (text.contains("第二轮问题")) {
                return 1000;
            }
            return text.contains("Human:") ? 20 : 10;
        });
        return counter;
    }
}
