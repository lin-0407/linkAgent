package com.link.linkagent.memory;

import com.link.linkagent.memory.model.ConversationMessageRecord;
import com.link.linkagent.memory.model.ConversationSessionRecord;
import com.link.linkagent.prompt.StubPromptService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryMemoryTest {

    private static final int TOKEN_THRESHOLD = 256_000;

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
    void shouldPersistAndReloadCompactionCheckpoint() {
        FakeConversationSessionMapper mapper = new FakeConversationSessionMapper();
        SummaryMemory memory = new SummaryMemory(
                new SummaryMemoryProperties(true, TOKEN_THRESHOLD, 2),
                fixedSummaryModel("summary"),
                new StubPromptService(),
                null,
                mapper);

        memory.recordCompactionCheckpoint("session-1", "压缩后的摘要", 12, 300_000, 4_000);

        // 摘要以 role=summary 的消息落库，释放的 token 数记在 token_count 上（供前端展示）
        assertThat(mapper.inserted).isNotNull();
        assertThat(mapper.inserted.getRole()).isEqualTo(SummaryMemory.SUMMARY_MESSAGE_ROLE);
        assertThat(mapper.inserted.getTokenCount()).isEqualTo(296_000);
        // 重新读摘要走数据库，模拟重启后仍能拿到上下文
        assertThat(memory.getSummary("session-1")).isEqualTo("压缩后的摘要");
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
        public List<ConversationMessageRecord> listMessagesBySession(String sessionId) {
            return List.of();
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
