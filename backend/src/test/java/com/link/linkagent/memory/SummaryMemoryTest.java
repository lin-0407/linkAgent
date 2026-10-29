package com.link.linkagent.memory;

import com.link.linkagent.memory.model.ConversationMessageRecord;
import com.link.linkagent.memory.model.ConversationSessionRecord;
import com.link.linkagent.prompt.StubPromptService;
import com.link.linkagent.llm.DeepSeekThinkingOptionsFactory;
import com.link.linkagent.llm.DeepSeekThinkingProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryMemoryTest {

    private static final int TOKEN_THRESHOLD = 256_000;

    @Test
    void shouldSendLowThinkingInTheDirectSummaryModelRequest() {
        AtomicReference<OpenAiChatOptions> captured = new AtomicReference<>();
        ChatModel model = prompt -> {
            captured.set((OpenAiChatOptions) prompt.getOptions());
            return new ChatResponse(List.of(new Generation(new AssistantMessage("摘要结果"))));
        };
        DeepSeekThinkingProperties properties = new DeepSeekThinkingProperties();
        properties.setDefaultModel("deepseek-v4-flash");
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, TOKEN_THRESHOLD, 2),
                model, new StubPromptService(), null, null, null, new DeepSeekThinkingOptionsFactory(properties, null));

        assertThat(memory.summarize("thinking-test", List.of(new MemoryMessage("Human", "历史事件"))))
                .isEqualTo("摘要结果");
        assertThat(captured.get().getReasoningEffort()).isEqualTo("low");
        assertThat(captured.get().getExtraBody()).containsEntry("thinking", Map.of("type", "enabled"));
    }

    @Test
    void shouldReturnEmptySummaryWhenDisabled() {
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(false, TOKEN_THRESHOLD, 2), fixedSummaryModel("summary"), new StubPromptService());

        memory.saveSummary("session-1", "user likes Java");

        assertThat(memory.getSummary("session-1")).isEmpty();
    }

    @Test
    void shouldKeepManualSummaryWhenEnabled() {
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, TOKEN_THRESHOLD, 2), fixedSummaryModel("summary"), new StubPromptService());

        memory.saveSummary("session-1", " user likes Java ");

        assertThat(memory.getSummary("session-1")).isEqualTo("user likes Java");
    }

    @Test
    void shouldNotCallModelWhenSummaryDisabled() {
        AtomicInteger callCount = new AtomicInteger();
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(false, TOKEN_THRESHOLD, 2), countingModel(callCount), new StubPromptService());

        String summary = memory.summarize("session-1", List.of(new MemoryMessage("Human", "first")));

        assertThat(summary).isNull();
        assertThat(callCount).hasValue(0);
        assertThat(memory.getSummary("session-1")).isEmpty();
    }

    @Test
    void shouldNotCallModelWhenMessagesAreEmpty() {
        AtomicInteger callCount = new AtomicInteger();
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, TOKEN_THRESHOLD, 2), countingModel(callCount), new StubPromptService());

        assertThat(memory.summarize("session-1", List.of())).isNull();
        assertThat(callCount).hasValue(0);
    }

    @Test
    void shouldSummarizeAndSaveWhenEnabled() {
        AtomicInteger callCount = new AtomicInteger();
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, TOKEN_THRESHOLD, 2), countingModel(callCount), new StubPromptService());

        String summary = memory.summarize("session-1", List.of(
                new MemoryMessage("Human", "first"),
                new MemoryMessage("AI", "second")
        ));

        assertThat(summary).isEqualTo("conversation summary");
        assertThat(callCount).hasValue(1);
        assertThat(memory.getSummary("session-1")).isEqualTo("conversation summary");
    }

    @Test
    void shouldMergeExistingSummaryIntoNextPrompt() {
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, TOKEN_THRESHOLD, 2),
                prompt -> {
                    capturedPrompt.set(prompt.getContents());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("合并后的摘要"))));
                },
                new StubPromptService());

        memory.saveSummary("session-1", "早期摘要：用户偏好 Java");
        String summary = memory.summarize("session-1", List.of(new MemoryMessage("Human", "第二轮")));

        assertThat(summary).isEqualTo("合并后的摘要");
        // 压缩后原文不再保留，第二次压缩必须把已有摘要合并进来，否则更早的历史会彻底丢失。
        assertThat(capturedPrompt.get())
                .contains("已有摘要")
                .contains("早期摘要：用户偏好 Java")
                .contains("Human: 第二轮");
    }

    @Test
    void shouldExposeConfiguredTokenThreshold() {
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, 1234, 2), fixedSummaryModel("summary"), new StubPromptService());

        assertThat(memory.getTokenThreshold()).isEqualTo(1234);
    }

    @Test
    void shouldReturnNonNegativeRetainedMessageCount() {
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, TOKEN_THRESHOLD, -1), fixedSummaryModel("summary"), new StubPromptService());

        assertThat(memory.getRetainedMessageCount()).isZero();
    }

    @Test
    void shouldExposeRetainedTokenBudgetDefaultAndClampNonPositive() {
        // 压缩后保留尾部默认按 16k token 预算（固定留 2 条会把用户问题和本轮工具证据裁光）
        assertThat(SummaryMemoryProperties.DEFAULT_RETAINED_TOKEN_BUDGET).isEqualTo(16_000);
        SummaryMemoryProperties defaults = new SummaryMemoryProperties(false, TOKEN_THRESHOLD, 2);
        assertThat(defaults.retainedTokenBudget()).isEqualTo(16_000);

        SummaryMemory memory = new SummaryMemory(defaults, fixedSummaryModel("summary"), new StubPromptService());
        assertThat(memory.getRetainedTokenBudget()).isEqualTo(16_000);

        // 0 表示退回按条数保留；负值没有语义，同样收敛到 0，避免裁剪时算出负下标
        SummaryMemory zeroBudget = new SummaryMemory(new SummaryMemoryProperties(true, TOKEN_THRESHOLD, 2, 0, 80),
                fixedSummaryModel("summary"), new StubPromptService());
        assertThat(zeroBudget.getRetainedTokenBudget()).isZero();
        SummaryMemory negativeBudget = new SummaryMemory(new SummaryMemoryProperties(true, TOKEN_THRESHOLD, 2, -100, 80),
                fixedSummaryModel("summary"), new StubPromptService());
        assertThat(negativeBudget.getRetainedTokenBudget()).isZero();
    }

    @Test
    void shouldRenderNativeToolCallPairWithPromptTextInSummaryPrompt() {
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, TOKEN_THRESHOLD, 2),
                prompt -> {
                    capturedPrompt.set(prompt.getContents());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("合并后的摘要"))));
                },
                new StubPromptService());

        memory.summarize("session-tool", List.of(
                new MemoryMessage("Human", "视频评论怎么样"),
                MemoryMessage.toolCall("AI", null, "call_1", "get_comments", "video=BV1xx,page=1"),
                MemoryMessage.toolResult(ShortTermMemory.ROLE_TOOL, null, "call_1", "get_comments", "共 42 条评论")));

        // 工具调用消息 content 为空，摘要提示词要靠 promptText 渲染出「调用 + 参数」，结果消息原样呈现
        assertThat(capturedPrompt.get())
                .contains("Human: 视频评论怎么样")
                .contains("AI: ToolCall: get_comments(video=BV1xx,page=1)")
                .contains("Tool: 共 42 条评论");
    }

    @Test
    void shouldPersistAndReloadCompactionCheckpoint() {
        FakeConversationSessionMapper mapper = new FakeConversationSessionMapper();
        SummaryMemory memory = new SummaryMemory(
                new SummaryMemoryProperties(true, TOKEN_THRESHOLD, 2),
                fixedSummaryModel("summary"),
                new StubPromptService(),
                null,
                null,
                mapper);

        memory.recordCompactionCheckpoint("session-1", "default", "压缩后的摘要", 12, 300_000, 4_000);

        // 摘要以 role=summary 的消息落库，释放的 token 数记在 token_count 上（供前端展示）
        assertThat(mapper.inserted).isNotNull();
        assertThat(mapper.inserted.getRole()).isEqualTo(SummaryMemory.SUMMARY_MESSAGE_ROLE);
        // 检查点也是消息表的一行，必须带 user_id，否则按用户捞增量时它会被漏掉（设计文档 C5）
        assertThat(mapper.inserted.getUserId()).isEqualTo("default");
        assertThat(mapper.inserted.getTokenCount()).isEqualTo(296_000);
        // 重新读摘要走数据库，模拟重启后仍能拿到上下文
        assertThat(memory.getSummary("session-1")).isEqualTo("压缩后的摘要");
    }

    @Test
    void shouldRegenerateOnceWithReviewIssuesThenAccept() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<String> secondPrompt = new AtomicReference<>();
        ChatModel model = prompt -> {
            int call = modelCalls.incrementAndGet();
            if (call == 2) {
                secondPrompt.set(prompt.getContents());
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("第 " + call + " 版摘要"))));
        };
        StubReviewer reviewer = new StubReviewer(
                SummaryReviewResult.fail(List.of("漏抽：用户要求示例用 Java")),
                SummaryReviewResult.pass());
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, TOKEN_THRESHOLD, 2),
                model, new StubPromptService(), reviewer, null, null);

        String summary = memory.summarize("session-1", List.of(new MemoryMessage("Human", "以后用 Java 举例")));

        assertThat(summary).isEqualTo("第 2 版摘要");
        assertThat(modelCalls).hasValue(2);
        // 重生成提示词必须带上复核问题，否则第二次还是同样的漏抽
        assertThat(secondPrompt.get()).contains("漏抽：用户要求示例用 Java");
        assertThat(memory.getSummary("session-1")).isEqualTo("第 2 版摘要");
    }

    @Test
    void shouldNotCompressWhenReviewFailsTwice() {
        AtomicInteger modelCalls = new AtomicInteger();
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, TOKEN_THRESHOLD, 2),
                countingModel(modelCalls), new StubPromptService(),
                new StubReviewer(
                        SummaryReviewResult.fail(List.of("编造：原文没有的结论")),
                        SummaryReviewResult.fail(List.of("编造：原文没有的结论"))),
                null, null);

        String summary = memory.summarize("session-1", List.of(new MemoryMessage("Human", "以后用 Java 举例")));

        // 复核两次都不通过：本轮不裁剪原文，被否掉的摘要也不能留在缓存里冒充已有摘要
        assertThat(summary).isNull();
        assertThat(modelCalls).hasValue(2);
        assertThat(memory.getSummary("session-1")).isEmpty();
    }

    @Test
    void shouldKeepExistingSummaryWhenReviewFails() {
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, TOKEN_THRESHOLD, 2),
                fixedSummaryModel("不合格的新摘要"), new StubPromptService(),
                new StubReviewer(
                        SummaryReviewResult.fail(List.of("编造：原文没有的结论")),
                        SummaryReviewResult.fail(List.of("编造：原文没有的结论"))),
                null, null);
        memory.saveSummary("session-1", "上一轮已通过的摘要");

        assertThat(memory.summarize("session-1", List.of(new MemoryMessage("Human", "新消息")))).isNull();
        // 复核不通过的新摘要不能覆盖上一轮已经通过复核的摘要
        assertThat(memory.getSummary("session-1")).isEqualTo("上一轮已通过的摘要");
    }

    @Test
    void shouldPassExistingSummaryToReviewer() {
        AtomicReference<String> reviewedExistingSummary = new AtomicReference<>();
        SummaryReviewer reviewer = new SummaryReviewer(null, null) {
            @Override
            public SummaryReviewResult review(SummaryGeneration generation, String existingSummary,
                                              List<MemoryMessage> messages) {
                reviewedExistingSummary.set(existingSummary);
                return SummaryReviewResult.pass();
            }
        };
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, TOKEN_THRESHOLD, 2),
                fixedSummaryModel("新摘要"), new StubPromptService(), reviewer, null, null);
        memory.saveSummary("session-1", "上一版摘要");

        memory.summarize("session-1", List.of(new MemoryMessage("Human", "新消息")));

        // 复核必须以「上一版摘要 + 本次消息」为基准：只给本次消息会把继承来的真实信息判成编造
        assertThat(reviewedExistingSummary.get()).isEqualTo("上一版摘要");
    }

    @Test
    void shouldReplayLastRequestPrefixAndAppendInstructionForCacheHit() {
        AtomicReference<List<org.springframework.ai.chat.messages.Message>> captured =
                new AtomicReference<>();
        AtomicReference<com.link.linkagent.llm.ChatRequestEnvelope> capturedEnvelope = new AtomicReference<>();
        com.link.linkagent.llm.LLMService llm = new com.link.linkagent.llm.LLMService() {
            @Override
            public com.link.linkagent.llm.LlmCallResult replayChat(
                    com.link.linkagent.llm.ChatRequestEnvelope envelope,
                    List<org.springframework.ai.chat.messages.Message> messages,
                    Integer maxOutputTokens) {
                capturedEnvelope.set(envelope);
                captured.set(messages);
                return new com.link.linkagent.llm.LlmCallResult("前缀复用摘要", "test-model", 10, 5, 15, 1L);
            }
        };
        SummaryMemory memory = new SummaryMemory(new SummaryMemoryProperties(true, TOKEN_THRESHOLD, 2),
                prompt -> {
                    throw new IllegalStateException("有信封时应通过 LLMService 重放，不应直连摘要模型");
                },
                new StubPromptService(), null, null, null, null, null, llm);
        List<org.springframework.ai.chat.messages.Message> realRequestMessages = List.of(
                new org.springframework.ai.chat.messages.UserMessage("上一轮真实请求消息"));
        com.link.linkagent.llm.ChatRequestEnvelope envelope =
                com.link.linkagent.llm.ChatRequestEnvelope.main("AGENT-SYSTEM", realRequestMessages, null);

        String summary = memory.summarize("session-replay", List.of(new MemoryMessage("Human", "旧消息")),
                new SummaryReplayPrefix(envelope));

        assertThat(summary).isEqualTo("前缀复用摘要");
        assertThat(capturedEnvelope.get()).isSameAs(envelope);
        List<org.springframework.ai.chat.messages.Message> messages = captured.get();
        // 缓存命中的前提：真实请求消息原样重放，指令只能作为追加消息放在最后
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isEqualTo(realRequestMessages.get(0));
        assertThat(messages.get(1).getText()).contains("摘要");
    }

    /** 按调用顺序返回预先排好的复核结论，用来验证「只重生成一次」的边界。 */
    private static class StubReviewer extends SummaryReviewer {

        private final List<SummaryReviewResult> answers;
        private final AtomicInteger calls = new AtomicInteger();

        StubReviewer(SummaryReviewResult... answers) {
            super(null, null);
            this.answers = List.of(answers);
        }

        @Override
        public SummaryReviewResult review(SummaryGeneration generation, String existingSummary,
                                          List<MemoryMessage> messages) {
            // 超出预置数量时复用最后一条，避免用例因为多调用了一次而越界
            return answers.get(Math.min(calls.getAndIncrement(), answers.size() - 1));
        }
    }

    private ChatModel fixedSummaryModel(String summary) {
        return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(summary))));
    }

    /** 只承载摘要检查点的假 mapper：其余方法本用例用不到，直接抛异常避免被误用。 */
    private static class FakeConversationSessionMapper implements ConversationSessionMapper {

        private ConversationMessageRecord inserted;

        @Override
        public int upsertSession(ConversationSessionRecord session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int insertMessage(ConversationMessageRecord message) {
            this.inserted = message;
            return 1;
        }

        @Override
        public List<ConversationSessionRecord> listSessionsByUser(String userId, int limit) {
            return List.of();
        }

        @Override
        public Optional<ConversationSessionRecord> findSessionById(String sessionId) {
            return Optional.empty();
        }

        @Override
        public List<ConversationMessageRecord> listMessagesBySession(String sessionId) {
            return List.of();
        }

        @Override
        public List<ConversationMessageRecord> listLatestMessagesAfterIdForUser(String userId, long afterId, int limit) {
            // 摘要链路不读增量消息，写了就是误用
            throw new UnsupportedOperationException();
        }

        @Override
        public long findHistoryBoundaryForNewSession(String userId, String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ConversationMessageRecord> listMessagesInRangeForUser(String userId, long afterId, long throughId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ConversationMessageRecord> findLatestSummary(String sessionId) {
            return Optional.ofNullable(inserted);
        }
    }

    private ChatModel countingModel(AtomicInteger callCount) {
        return prompt -> {
            callCount.incrementAndGet();
            return fixedSummaryModel("conversation summary").call(prompt);
        };
    }
}
