package com.link.linkagent.core;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.LlmCallResult;
import com.link.linkagent.llm.StructuredCallResult;
import com.link.linkagent.memory.InMemoryShortTermMemoryStore;
import com.link.linkagent.memory.LongTermMemory;
import com.link.linkagent.memory.LongTermMemoryCandidate;
import com.link.linkagent.memory.LongTermMemoryExtractor;
import com.link.linkagent.memory.LongTermMemoryMapper;
import com.link.linkagent.memory.LongTermMemoryRecord;
import com.link.linkagent.memory.ShortTermMemory;
import com.link.linkagent.memory.SummaryMemory;
import com.link.linkagent.memory.SummaryMemoryProperties;
import com.link.linkagent.prompt.StubPromptService;
import com.link.linkagent.tool.ToolExecutionProperties;
import com.link.linkagent.tool.ToolExecutor;
import com.link.linkagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutorLongTermMemoryTest {

    @Test
    void shouldAppendDefaultUserLongTermMemoryToPrompt() {
        CapturingLlmService llmService = new CapturingLlmService();
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        AgentExecutor executor = new AgentExecutor(
                llmService,
                new ToolRegistry(List.of()),
                emptyToolExecutor(),
                new ShortTermMemory(new InMemoryShortTermMemoryStore()),
                new SummaryMemory(new SummaryMemoryProperties(false, 8, 2), prompt -> new ChatResponse(List.of()), new StubPromptService()),
                new LongTermMemory(mapper),
                new NoopLongTermMemoryExtractor(),
                new StubPromptService()
        );

        executor.run("session-1", "请根据我的偏好回答");

        assertThat(mapper.listUserId).isEqualTo("default");
        assertThat(llmService.lastUserMessage)
                .contains("Long-term memory:")
                .contains("user.preference.language: 用户偏好使用 Java")
                .contains("Human:请根据我的偏好回答");
    }

    @Test
    void shouldUseRequestUserIdWhenProvided() {
        CapturingLlmService llmService = new CapturingLlmService();
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        AgentExecutor executor = new AgentExecutor(
                llmService,
                new ToolRegistry(List.of()),
                emptyToolExecutor(),
                new ShortTermMemory(new InMemoryShortTermMemoryStore()),
                new SummaryMemory(new SummaryMemoryProperties(false, 8, 2), prompt -> new ChatResponse(List.of()), new StubPromptService()),
                new LongTermMemory(mapper),
                new NoopLongTermMemoryExtractor(),
                new StubPromptService()
        );

        executor.run("session-1", " user-1 ", "hello");

        assertThat(mapper.listUserId).isEqualTo("user-1");
    }

    @Test
    void shouldExtractAndSaveLongTermMemoryAfterFinalAnswer() {
        CapturingLlmService llmService = new CapturingLlmService();
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        AgentExecutor executor = new AgentExecutor(
                llmService,
                new ToolRegistry(List.of()),
                emptyToolExecutor(),
                new ShortTermMemory(new InMemoryShortTermMemoryStore()),
                new SummaryMemory(new SummaryMemoryProperties(false, 8, 2), prompt -> new ChatResponse(List.of()), new StubPromptService()),
                new LongTermMemory(mapper),
                new FixedLongTermMemoryExtractor(),
                new StubPromptService()
        );

        executor.run("session-1", "user-1", "以后请优先用 Java 举例");

        assertThat(mapper.savedRecord.getUserId()).isEqualTo("user-1");
        assertThat(mapper.savedRecord.getMemoryKey()).isEqualTo("user.preference.example_language");
        assertThat(mapper.savedRecord.getContent()).isEqualTo("用户希望后续回答优先使用 Java 示例");
        assertThat(mapper.savedRecord.getSourceSessionId()).isEqualTo("session-1");
    }

    @Test
    void shouldRecordSingleDiagnosticStepWhenLlmMissesReactFormat() {
        SequencedLlmService llmService = new SequencedLlmService(
                "我直接给你一个没有 ReAct 标记的回答",
                """
                        Thought:我现在已经掌握了所需信息
                        Final Answer:已经恢复为正确格式
                        """
        );
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        AgentExecutor executor = new AgentExecutor(
                llmService,
                new ToolRegistry(List.of()),
                emptyToolExecutor(),
                new ShortTermMemory(new InMemoryShortTermMemoryStore()),
                new SummaryMemory(new SummaryMemoryProperties(false, 8, 2), prompt -> new ChatResponse(List.of()), new StubPromptService()),
                new LongTermMemory(mapper),
                new NoopLongTermMemoryExtractor(),
                new StubPromptService()
        );

        var response = executor.run("session-1", "user-1", "测试格式漂移");

        assertThat(response.finalAnswer()).isEqualTo("已经恢复为正确格式");
        assertThat(response.steps()).hasSize(1);
        AgentStep step = response.steps().get(0);
        assertThat(step.stepNumber()).isEqualTo(1);
        assertThat(step.thought()).contains("未解析到 Thought");
        assertThat(step.observation()).contains("格式错误");
        assertThat(llmService.callCount).isEqualTo(2);
    }

    @Test
    void shouldUseStructuredKernelWhenDefaultEnabled() {
        StructuredLlmService llmService = new StructuredLlmService(new ReActStep(
                "我已经掌握了所需信息",
                null,
                null,
                "结构化回答"
        ));
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        AgentExecutor executor = new AgentExecutor(
                llmService,
                new ToolRegistry(List.of()),
                emptyToolExecutor(),
                new ShortTermMemory(new InMemoryShortTermMemoryStore()),
                new SummaryMemory(new SummaryMemoryProperties(false, 8, 2), prompt -> new ChatResponse(List.of()), new StubPromptService()),
                new LongTermMemory(mapper),
                new NoopLongTermMemoryExtractor(),
                new StubPromptService(),
                true
        );

        var response = executor.run("session-1", "user-1", "测试结构化内核");

        assertThat(response.finalAnswer()).isEqualTo("结构化回答");
        assertThat(response.steps()).isEmpty();
        assertThat(llmService.structuredCallCount).isEqualTo(1);
        assertThat(llmService.textCallCount).isZero();
    }

    private static class CapturingLlmService extends LLMService {

        private String lastUserMessage;

        CapturingLlmService() {
            super();
        }

        @Override
        public String chat(String systemPrompt, String userMessage) {
            this.lastUserMessage = userMessage;
            return """
                    Thought:我现在已经掌握了所需信息
                    Final Answer:好的
                    """;
        }

        @Override
        public LlmCallResult chatWithUsage(String systemPrompt, String userMessage) {
            return new LlmCallResult(chat(systemPrompt, userMessage), "test-model", 3, 5, 8, 1L);
        }
    }

    private static class SequencedLlmService extends LLMService {

        private final List<String> responses;
        private int callCount;

        SequencedLlmService(String... responses) {
            super();
            this.responses = List.of(responses);
        }

        @Override
        public String chat(String systemPrompt, String userMessage) {
            String response = responses.get(Math.min(callCount, responses.size() - 1));
            callCount++;
            return response;
        }

        @Override
        public LlmCallResult chatWithUsage(String systemPrompt, String userMessage) {
            return new LlmCallResult(chat(systemPrompt, userMessage), "test-model", 2, 4, 6, 1L);
        }
    }

    private static class StructuredLlmService extends LLMService {

        private final ReActStep response;
        private int structuredCallCount;
        private int textCallCount;

        StructuredLlmService(ReActStep response) {
            super();
            this.response = response;
        }

        @Override
        public String chat(String systemPrompt, String userMessage) {
            textCallCount++;
            return """
                    Thought:我现在已经掌握了所需信息
                    Final Answer:文本回答
                    """;
        }

        @Override
        public LlmCallResult chatWithUsage(String systemPrompt, String userMessage) {
            return new LlmCallResult(chat(systemPrompt, userMessage), "test-model", 3, 5, 8, 1L);
        }

        @Override
        public <T> StructuredCallResult<T> chatStructuredWithUsage(String systemPrompt, String userMessage, Class<T> type) {
            structuredCallCount++;
            return new StructuredCallResult<>(type.cast(response), 7, 11, 18, 1L);
        }
    }

    private ToolExecutor emptyToolExecutor() {
        return new ToolExecutor(new ToolRegistry(List.of()), new ToolExecutionProperties(10, 0));
    }

    private static class FakeLongTermMemoryMapper implements LongTermMemoryMapper {

        private String listUserId;
        private LongTermMemoryRecord savedRecord;

        @Override
        public int upsert(LongTermMemoryRecord record) {
            this.savedRecord = record;
            return 1;
        }

        @Override
        public Optional<LongTermMemoryRecord> findByKey(String userId, String memoryKey) {
            return Optional.empty();
        }

        @Override
        public List<LongTermMemoryRecord> listByUser(String userId, int limit) {
            this.listUserId = userId;
            LongTermMemoryRecord record = new LongTermMemoryRecord();
            record.setUserId(userId);
            record.setMemoryKey("user.preference.language");
            record.setContent("用户偏好使用 Java");
            return List.of(record);
        }

        @Override
        public int softDelete(String userId, String memoryKey) {
            return 1;
        }
    }

    private static class NoopLongTermMemoryExtractor extends LongTermMemoryExtractor {

        NoopLongTermMemoryExtractor() {
            super(new CapturingLlmService(), new StubPromptService());
        }

        @Override
        public Optional<LongTermMemoryCandidate> extract(String userMessage, String finalAnswer) {
            return Optional.empty();
        }
    }

    private static class FixedLongTermMemoryExtractor extends LongTermMemoryExtractor {

        FixedLongTermMemoryExtractor() {
            super(new CapturingLlmService(), new StubPromptService());
        }

        @Override
        public Optional<LongTermMemoryCandidate> extract(String userMessage, String finalAnswer) {
            return Optional.of(new LongTermMemoryCandidate(
                    true,
                    "user.preference.example_language",
                    "用户希望后续回答优先使用 Java 示例"
            ));
        }
    }
}
