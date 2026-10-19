package com.link.linkagent.core;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.LlmCallResult;
import com.link.linkagent.llm.StructuredCallResult;
import com.link.linkagent.llm.StrictToolCall;
import com.link.linkagent.llm.ToolCallingCallResult;
import com.link.linkagent.memory.InMemoryShortTermMemoryStore;
import com.link.linkagent.memory.LongTermMemory;
import com.link.linkagent.memory.LongTermMemoryCandidate;
import com.link.linkagent.memory.LongTermMemoryExtractionResult;
import com.link.linkagent.memory.LongTermMemoryExtractor;
import com.link.linkagent.memory.LongTermMemoryExtractionCoordinator;
import com.link.linkagent.memory.LongTermMemoryMapper;
import com.link.linkagent.memory.MemoryMessage;
import com.link.linkagent.memory.LongTermMemoryRecord;
import com.link.linkagent.memory.ShortTermMemory;
import com.link.linkagent.memory.SummaryMemory;
import com.link.linkagent.memory.SummaryMemoryProperties;
import com.link.linkagent.prompt.StubPromptService;
import com.link.linkagent.tool.Tool;
import com.link.linkagent.tool.ToolExecutionProperties;
import com.link.linkagent.tool.ToolExecutor;
import com.link.linkagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentExecutorLongTermMemoryTest {

    @Test
    void shouldCompleteOldHistoryBeforeRetrievingMemoryForNewSession() {
        LongTermMemory longTerm = mock(LongTermMemory.class);
        LongTermMemoryExtractionCoordinator coordinator = mock(LongTermMemoryExtractionCoordinator.class);
        ShortTermMemory recent = new ShortTermMemory(new InMemoryShortTermMemoryStore());
        SummaryMemory summary = mock(SummaryMemory.class);
        AgentExecutor executor = new AgentExecutor(mock(LLMService.class), new ToolRegistry(List.of()), emptyToolExecutor(),
                recent, summary, longTerm, null, new StubPromptService(), null,
                null, null, null, null, null, null, null, null, coordinator);

        executor.prepareMemoryContext("new-session", "default", "首次保养后出了什么问题");

        var order = inOrder(coordinator, longTerm);
        order.verify(coordinator).completeHistoryBeforeFirstRead("default", "new-session");
        order.verify(longTerm).retrieveForContext("default", "首次保养后出了什么问题");
        clearInvocations(coordinator);
        recent.append("new-session", "Human", "已有对话");
        executor.prepareMemoryContext("new-session", "default", "继续聊");
        verifyNoInteractions(coordinator);
    }

    @Test
    void platformContextMustGuideThisTurnWithoutBecomingUserMemory() {
        var llm=new CapturingLlmService();
        var memory=new ShortTermMemory(new InMemoryShortTermMemoryStore());
        var executor=new AgentExecutor(llm,new ToolRegistry(List.of()),emptyToolExecutor(),memory,
                new SummaryMemory(new SummaryMemoryProperties(false,256_000,2),prompt -> new ChatResponse(List.of()),new StubPromptService()),
                new LongTermMemory(new FakeLongTermMemoryMapper()),new NoopLongTermMemoryExtractor(),new StubPromptService());
        executor.run("assistant-context-test","default","我现在应该做什么",AgentExecutionMode.REACT,
                "平台助手规则：当前任务尚未完成成片试映");
        assertThat(llm.lastSystemPrompt).contains("当前任务尚未完成成片试映");
        assertThat(memory.getRecentMessages("assistant-context-test")).extracting(MemoryMessage::content)
                .contains("我现在应该做什么","好的")
                .noneMatch(value -> value.contains("当前任务尚未完成成片试映"));
    }

    @Test
    void shouldAppendDefaultUserLongTermMemoryToPrompt() {
        CapturingLlmService llmService = new CapturingLlmService();
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        AgentExecutor executor = new AgentExecutor(
                llmService,
                new ToolRegistry(List.of()),
                emptyToolExecutor(),
                new ShortTermMemory(new InMemoryShortTermMemoryStore()),
                new SummaryMemory(new SummaryMemoryProperties(false, 256_000, 2), prompt -> new ChatResponse(List.of()), new StubPromptService()),
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
    void shouldEmitLongTermMemoryInStableOrderInPrompt() {
        CapturingLlmService llmService = new CapturingLlmService();
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        // id=2 分数更高（重要性 90 > 10），但拼进 Prompt 的顺序必须是 id 升序（D12），否则前缀缓存每轮作废
        mapper.retrievalRecords = List.of(
                memory(2L, "project.profile.summary", "项目是个人工作台", 90),
                memory(1L, "user.preference.language", "用户偏好使用 Java", 10));
        AgentExecutor executor = new AgentExecutor(
                llmService,
                new ToolRegistry(List.of()),
                emptyToolExecutor(),
                new ShortTermMemory(new InMemoryShortTermMemoryStore()),
                new SummaryMemory(new SummaryMemoryProperties(false, 256_000, 2), prompt -> new ChatResponse(List.of()), new StubPromptService()),
                new LongTermMemory(mapper),
                new NoopLongTermMemoryExtractor(),
                new StubPromptService()
        );

        executor.run("session-1", "user-1", "随便问一句");

        assertThat(llmService.lastUserMessage.indexOf("用户偏好使用 Java"))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(llmService.lastUserMessage.indexOf("项目是个人工作台"));
    }

    private static LongTermMemoryRecord memory(Long id, String memoryKey, String content, int importance) {
        LongTermMemoryRecord record = new LongTermMemoryRecord();
        record.setId(id);
        record.setUserId("default");
        record.setMemoryKey(memoryKey);
        record.setContent(content);
        record.setImportance(importance);
        return record;
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
                new SummaryMemory(new SummaryMemoryProperties(false, 256_000, 2), prompt -> new ChatResponse(List.of()), new StubPromptService()),
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
                new SummaryMemory(new SummaryMemoryProperties(false, 256_000, 2), prompt -> new ChatResponse(List.of()), new StubPromptService()),
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
                new SummaryMemory(new SummaryMemoryProperties(false, 256_000, 2), prompt -> new ChatResponse(List.of()), new StubPromptService()),
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
        assertThat(llmService.lastUserMessage)
                .contains("AI:\n我直接给你一个没有 ReAct 标记的回答")
                .contains("即使最终内容是 JSON，也不能省略 Final Answer: 前缀");
    }

    /** 验证任务模式会针对裸 JSON 给出精确纠错，并在下一轮取得合法 Final Answer。 */
    @Test
    void shouldCorrectBareJsonInTaskMode() {
        SequencedLlmService llmService = new SequencedLlmService(
                "{\"options\":[]}",
                "Final Answer: {\"options\":[]}"
        );
        AgentExecutor executor = new AgentExecutor(
                llmService,
                new ToolRegistry(List.of()),
                emptyToolExecutor(),
                new ShortTermMemory(new InMemoryShortTermMemoryStore()),
                new SummaryMemory(new SummaryMemoryProperties(false, 256_000, 2),
                        prompt -> new ChatResponse(List.of()), new StubPromptService()),
                new LongTermMemory(new FakeLongTermMemoryMapper()),
                new NoopLongTermMemoryExtractor(),
                new StubPromptService()
        );

        var response = executor.runTask("生成 JSON");

        assertThat(response.finalAnswer()).isEqualTo("{\"options\":[]}");
        assertThat(response.steps()).hasSize(1);
        assertThat(llmService.callCount).isEqualTo(2);
        assertThat(llmService.lastUserMessage)
                .contains("AI:\n{\"options\":[]}")
                .contains("Final Answer: 最终内容");
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
                new SummaryMemory(new SummaryMemoryProperties(false, 256_000, 2), prompt -> new ChatResponse(List.of()), new StubPromptService()),
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

    @Test
    void shouldExecuteNativeToolCallAndReturnFinalContent() {
        StrictToolCallingLlmService llmService = new StrictToolCallingLlmService();
        CapturingTool tool = new CapturingTool();
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        registry.init();
        AgentExecutor executor = new AgentExecutor(
                llmService,
                registry,
                new ToolExecutor(registry, new ToolExecutionProperties(10, 0)),
                new ShortTermMemory(new InMemoryShortTermMemoryStore()),
                new SummaryMemory(new SummaryMemoryProperties(false, 256_000, 2),
                        prompt -> new ChatResponse(List.of()), new StubPromptService()),
                new LongTermMemory(new FakeLongTermMemoryMapper()),
                new NoopLongTermMemoryExtractor(),
                new StubPromptService(),
                true
        );

        var response = executor.runTask("调用测试工具");

        assertThat(response.finalAnswer()).isEqualTo("工具结果已经确认");
        assertThat(response.steps()).hasSize(1);
        assertThat(response.steps().get(0).action()).isEqualTo("echo");
        assertThat(response.steps().get(0).thought()).isNull();
        assertThat(tool.input).isEqualTo("测试参数");
        assertThat(llmService.callCount).isEqualTo(2);
    }

    @Test
    void shouldContinueStructuredLoopWithObservationWhenStrictCallFails() {
        StrictThenFallbackLlmService llmService = new StrictThenFallbackLlmService();
        CapturingTool tool = new CapturingTool();
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        registry.init();
        AgentExecutor executor = new AgentExecutor(
                llmService,
                registry,
                new ToolExecutor(registry, new ToolExecutionProperties(10, 0)),
                new ShortTermMemory(new InMemoryShortTermMemoryStore()),
                new SummaryMemory(new SummaryMemoryProperties(false, 256_000, 2),
                        prompt -> new ChatResponse(List.of()), new StubPromptService()),
                new LongTermMemory(new FakeLongTermMemoryMapper()),
                new NoopLongTermMemoryExtractor(),
                new StubPromptService(),
                true
        );

        var response = executor.runTask("调用测试工具后完成回答");

        assertThat(response.finalAnswer()).isEqualTo("已从旧链路完成回答");
        assertThat(response.steps()).hasSize(1);
        assertThat(tool.input).isEqualTo("测试参数");
        assertThat(llmService.strictCallCount).isEqualTo(2);
        assertThat(llmService.structuredCallCount).isEqualTo(1);
        assertThat(llmService.lastStructuredMessage)
                .contains("Action:echo")
                .contains("Observation:echo:echo:测试参数");
    }

    private static class CapturingLlmService extends LLMService {

        private String lastUserMessage;
        private String lastSystemPrompt;

        CapturingLlmService() {
            super();
        }

        @Override
        public String chat(String systemPrompt, String userMessage) {
            this.lastUserMessage = userMessage;
            this.lastSystemPrompt = systemPrompt;
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
        private String lastUserMessage;

        SequencedLlmService(String... responses) {
            super();
            this.responses = List.of(responses);
        }

        @Override
        public String chat(String systemPrompt, String userMessage) {
            lastUserMessage = userMessage;
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

    private static class StrictToolCallingLlmService extends LLMService {

        private int callCount;

        @Override
        public boolean isStrictFunctionCallingEnabled() {
            return true;
        }

        @Override
        public ToolCallingCallResult chatWithStrictToolsWithUsage(String systemPrompt,
                                                                  List<Message> messages,
                                                                  Collection<Tool> tools) {
            callCount++;
            if (callCount == 1) {
                AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        LLMService.EXECUTE_TOOL_FUNCTION,
                        "{\"toolName\":\"echo\",\"input\":\"测试参数\"}"
                );
                AssistantMessage assistant = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(toolCall))
                        .build();
                return new ToolCallingCallResult(
                        assistant,
                        "",
                        List.of(new StrictToolCall(
                                "call-1", LLMService.EXECUTE_TOOL_FUNCTION, "echo", "测试参数"
                        )),
                        4, 2, 6, 1L
                );
            }
            AssistantMessage assistant = AssistantMessage.builder()
                    .content("工具结果已经确认")
                    .toolCalls(List.of())
                    .build();
            return new ToolCallingCallResult(
                    assistant, "工具结果已经确认", List.of(), 5, 3, 8, 1L
            );
        }
    }

    private static class StrictThenFallbackLlmService extends LLMService {

        private int strictCallCount;
        private int structuredCallCount;
        private String lastStructuredMessage;

        @Override
        public boolean isStrictFunctionCallingEnabled() {
            return true;
        }

        @Override
        public ToolCallingCallResult chatWithStrictToolsWithUsage(String systemPrompt,
                                                                  List<Message> messages,
                                                                  Collection<Tool> tools) {
            strictCallCount++;
            if (strictCallCount > 1) {
                throw new IllegalStateException("Beta 端点不可用");
            }
            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    "call-1",
                    "function",
                    LLMService.EXECUTE_TOOL_FUNCTION,
                    "{\"toolName\":\"echo\",\"input\":\"测试参数\"}"
            );
            AssistantMessage assistant = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(toolCall))
                    .build();
            return new ToolCallingCallResult(
                    assistant,
                    "",
                    List.of(new StrictToolCall(
                            "call-1", LLMService.EXECUTE_TOOL_FUNCTION, "echo", "测试参数"
                    )),
                    4, 2, 6, 1L
            );
        }

        @Override
        public <T> StructuredCallResult<T> chatStructuredWithUsage(String systemPrompt,
                                                                   String userMessage,
                                                                   Class<T> type) {
            structuredCallCount++;
            lastStructuredMessage = userMessage;
            ReActStep response = new ReActStep(null, null, null, "已从旧链路完成回答");
            return new StructuredCallResult<>(type.cast(response), 5, 3, 8, 1L);
        }
    }

    private static class CapturingTool implements Tool {

        private String input;

        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public String getDescription() {
            return "返回输入内容";
        }

        @Override
        public String execute(String input) {
            this.input = input;
            return "echo:" + input;
        }
    }

    private ToolExecutor emptyToolExecutor() {
        return new ToolExecutor(new ToolRegistry(List.of()), new ToolExecutionProperties(10, 0));
    }

    private static class FakeLongTermMemoryMapper implements LongTermMemoryMapper {

        private String listUserId;
        private LongTermMemoryRecord savedRecord;
        private List<LongTermMemoryRecord> retrievalRecords;

        @Override
        public int insert(LongTermMemoryRecord record) {
            this.savedRecord = record;
            // 模拟数据库回填自增主键：步骤 6 起 id 是记忆的身份，写入后必须能拿到它
            record.setId(1L);
            return 1;
        }

        @Override
        public Optional<LongTermMemoryRecord> findActiveById(String userId, Long id) {
            return Optional.ofNullable(savedRecord);
        }

        @Override
        public List<LongTermMemoryRecord> listByUser(String userId) {
            this.listUserId = userId;
            return List.of();
        }

        @Override
        public List<LongTermMemoryRecord> listActiveForRetrieval(String userId) {
            // 检索入口：步骤 8 起上下文里的长期记忆来自这里，不再是「最近 N 条」
            this.listUserId = userId;
            if (retrievalRecords != null) {
                return retrievalRecords;
            }
            return List.of(memory(1L, "user.preference.language", "用户偏好使用 Java", 80));
        }

        @Override
        public int updateEmbedding(String userId, Long id, String embedding) {
            return 1;
        }

        @Override
        public int markAccessed(String userId, List<Long> ids, java.time.LocalDateTime accessedTime) {
            return ids.size();
        }

        @Override
        public int updateContent(String userId, Long id, String content, int importance) {
            return 1;
        }

        @Override
        public int markDeletedByDecision(String userId, Long id, Long supersededBy) {
            return 1;
        }

        @Override
        public int softDelete(String userId, Long id) {
            return 1;
        }

        @Override
        public int restore(String userId, Long id) {
            return 1;
        }
    }

    private static class NoopLongTermMemoryExtractor extends LongTermMemoryExtractor {

        NoopLongTermMemoryExtractor() {
            super(new CapturingLlmService(), new StubPromptService());
        }

        @Override
        public LongTermMemoryExtractionResult extract(List<MemoryMessage> messages) {
            return LongTermMemoryExtractionResult.noMemory();
        }
    }

    private static class FixedLongTermMemoryExtractor extends LongTermMemoryExtractor {

        FixedLongTermMemoryExtractor() {
            super(new CapturingLlmService(), new StubPromptService());
        }

        @Override
        public LongTermMemoryExtractionResult extract(List<MemoryMessage> messages) {
            return LongTermMemoryExtractionResult.remembered(new LongTermMemoryCandidate(
                    true,
                    "user.preference.example_language",
                    "用户希望后续回答优先使用 Java 示例"
            ));
        }
    }
}
