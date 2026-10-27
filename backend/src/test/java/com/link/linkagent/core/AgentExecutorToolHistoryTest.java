package com.link.linkagent.core;

import com.link.linkagent.dto.AgentChatResponse;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.LlmCallResult;
import com.link.linkagent.llm.StructuredCallResult;
import com.link.linkagent.memory.AgentTraceMapper;
import com.link.linkagent.memory.ConversationSessionMapper;
import com.link.linkagent.memory.InMemoryShortTermMemoryStore;
import com.link.linkagent.memory.LongTermMemory;
import com.link.linkagent.memory.LongTermMemoryExtractionCoordinator;
import com.link.linkagent.memory.MemoryMessage;
import com.link.linkagent.memory.ShortTermMemory;
import com.link.linkagent.memory.SummaryMemory;
import com.link.linkagent.memory.SummaryMemoryProperties;
import com.link.linkagent.memory.model.AgentStepRecord;
import com.link.linkagent.memory.model.AgentTraceRecord;
import com.link.linkagent.memory.model.ConversationMessageRecord;
import com.link.linkagent.core.plan.PlanAndExecuteAgent;
import com.link.linkagent.prompt.StubPromptService;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.tool.Tool;
import com.link.linkagent.tool.ToolExecutionProperties;
import com.link.linkagent.tool.ToolExecutor;
import com.link.linkagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具执行结果写入会话历史的回归测试（OpenAI/DeepSeek 原生协议两条消息）。
 *
 * <p>锁定的生产行为（2026-10 原生协议改造）：
 * <ol>
 *   <li>每个工具步骤落两行：assistant 工具调用消息（content 空，tool_name/tool_arguments/tool_call_id 有值）
 *       + 紧随其后的 tool 结果消息（content=结果，同一 tool_call_id）；一轮 2 个步骤共 6 行；</li>
 *   <li>短期记忆按同样顺序：Human → AI(ToolCall: 工具(参数)) → Tool(结果) → … → AI(最终回答)，
 *       调用消息 content 为空、toolArguments 非空，pair 字段用 MemoryMessage 断言；</li>
 *   <li>参数摘要 ≤300 字、结果 ≤2000 字，原文硬截断；t_agent_step 照旧保留完整 thought/tool_input/tool_output；</li>
 *   <li>非工具步骤（格式错误/thought-only/blank action）不产生任何工具行；取消轮只留用户消息；</li>
 *   <li>tool_name 列只放工具名并受 64 字符列宽限制；长期记忆 afterTurn 的区间终点仍是最终 assistant 消息 id。</li>
 * </ol>
 *
 * <p>测试走「全参构造器 + 文本/结构化 ReAct」路径：全参构造器才能拿到消息表/trace 的写入口；
 * 用 RuntimeSettingService 的 mock 切换结构化内核开关，两条路径共用同一个 persistChatTurn。
 */
class AgentExecutorToolHistoryTest {

    private static final String SESSION_ID = "tool-history-session";
    private static final String USER_ID = "tool-history-user";
    private static final String USER_MESSAGE = "统计这条视频的评论";

    @Test
    void shouldPersistTwoToolStepsAsSixNativeProtocolMessages() {
        SequencedLlmService llm = new SequencedLlmService(
                "Thought:先查评论\nAction:echo\nAction Input:参数甲",
                "Thought:再查弹幕\nAction:echo\nAction Input:参数乙",
                "Thought:信息已经够了\nFinal Answer:最终回答");
        Harness harness = harness(llm, new EchoTool());

        AgentChatResponse response = harness.executor().run(SESSION_ID, USER_ID, USER_MESSAGE,
                AgentExecutionMode.REACT);

        assertThat(response.finalAnswer()).isEqualTo("最终回答");
        assertThat(response.steps()).hasSize(2);

        // 消息表：user → assistant(调用) → tool(结果) → assistant(调用) → tool(结果) → assistant(最终回答)
        List<ConversationMessageRecord> saved = harness.messages();
        assertThat(saved).extracting(ConversationMessageRecord::getRole)
                .containsExactly("user", "assistant", "tool", "assistant", "tool", "assistant");
        assertThat(saved).extracting(ConversationMessageRecord::getContent)
                .containsExactly(USER_MESSAGE, "", "echo:参数甲", "", "echo:参数乙", "最终回答");

        ConversationMessageRecord firstCall = saved.get(1);
        ConversationMessageRecord firstResult = saved.get(2);
        ConversationMessageRecord secondCall = saved.get(3);
        ConversationMessageRecord secondResult = saved.get(4);
        // assistant 调用行：正文为空、三列有值、不写 thought
        assertThat(firstCall.getContent()).isEmpty();
        assertThat(firstCall.getToolName()).isEqualTo("echo");
        assertThat(firstCall.getToolArguments()).isEqualTo("参数甲");
        assertThat(firstCall.getToolCallId()).startsWith("call_").hasSize(37);
        // tool 结果行：正文是结果、同 tool_call_id/tool_name、tool_arguments 为空
        assertThat(firstResult.getContent()).isEqualTo("echo:参数甲");
        assertThat(firstResult.getToolCallId()).isEqualTo(firstCall.getToolCallId());
        assertThat(firstResult.getToolName()).isEqualTo("echo");
        assertThat(firstResult.getToolArguments()).isNull();
        // 两个步骤的配对 id 不同，且各自成对
        assertThat(secondCall.getToolCallId()).startsWith("call_").isNotEqualTo(firstCall.getToolCallId());
        assertThat(secondResult.getToolCallId()).isEqualTo(secondCall.getToolCallId());
        assertThat(secondCall.getToolArguments()).isEqualTo("参数乙");
        assertThat(secondResult.getContent()).isEqualTo("echo:参数乙");
        // 工具消息不记 token；3 次模型调用每次 8 token，累计值记在最终回答上
        assertThat(saved.subList(1, 5)).extracting(ConversationMessageRecord::getTokenCount).containsOnly(0);
        assertThat(saved.get(5).getTokenCount()).isEqualTo(24);
        assertThat(saved.get(5).getToolCallId()).isNull();
        assertThat(saved.get(5).getToolArguments()).isNull();
        assertThat(saved.stream().map(ConversationMessageRecord::getContent))
                .noneMatch(content -> content.contains("先查评论") || content.contains("再查弹幕"));

        // 短期记忆：调用消息 content 为空、toolArguments 非空，结果消息用同一个 toolCallId 配对
        List<MemoryMessage> recent = harness.memory().getRecentMessages(SESSION_ID);
        assertThat(recent).extracting(MemoryMessage::role)
                .containsExactly("Human", "AI", "Tool", "AI", "Tool", "AI");
        MemoryMessage callOne = recent.get(1);
        MemoryMessage resultOne = recent.get(2);
        assertThat(callOne.content()).isEmpty();
        assertThat(callOne.isToolCall()).isTrue();
        assertThat(callOne.toolCallId()).isEqualTo(firstCall.getToolCallId());
        assertThat(callOne.toolName()).isEqualTo("echo");
        assertThat(callOne.toolArguments()).isEqualTo("参数甲");
        assertThat(callOne.promptText()).isEqualTo("ToolCall: echo(参数甲)");
        assertThat(resultOne.content()).isEqualTo("echo:参数甲");
        assertThat(resultOne.isToolResult()).isTrue();
        assertThat(resultOne.toolCallId()).isEqualTo(callOne.toolCallId());
        assertThat(resultOne.toolName()).isEqualTo("echo");
        assertThat(resultOne.toolArguments()).isNull();
        MemoryMessage callTwo = recent.get(3);
        MemoryMessage resultTwo = recent.get(4);
        assertThat(callTwo.toolCallId()).isNotEqualTo(callOne.toolCallId());
        assertThat(callTwo.toolArguments()).isEqualTo("参数乙");
        assertThat(resultTwo.toolCallId()).isEqualTo(callTwo.toolCallId());
        assertThat(resultTwo.content()).isEqualTo("echo:参数乙");
        assertThat(recent.get(5).content()).isEqualTo("最终回答");
        assertThat(recent.get(5).toolCallId()).isNull();
        assertThat(recent.get(5).toolArguments()).isNull();
        // 消息 id 按发生顺序递增，长期记忆游标仍以最终 assistant 消息为终点
        assertThat(recent).extracting(MemoryMessage::messageId)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        verify(harness.coordinator()).afterTurn(USER_ID, SESSION_ID, USER_MESSAGE, "最终回答", 6L);

        // t_agent_step 保持原样：action 步骤带完整 thought/工具名/入参/出参，final 步骤独立一条
        assertThat(harness.traceSteps()).extracting(AgentStepRecord::getStepType)
                .containsExactly("action", "action", "final");
        AgentStepRecord firstAction = harness.traceSteps().get(0);
        assertThat(firstAction.getContent()).isEqualTo("先查评论");
        assertThat(firstAction.getToolName()).isEqualTo("echo");
        assertThat(firstAction.getToolInput()).isEqualTo("参数甲");
        assertThat(firstAction.getToolOutput()).isEqualTo("echo:参数甲");
        assertThat(harness.traceSteps().get(2).getToolName()).isNull();
        assertThat(harness.traceSteps().get(2).getContent()).isEqualTo("最终回答");
    }

    @Test
    void shouldTruncateToolArgumentsAndResultInNativeProtocolMessages() {
        String longArguments = "参数" + "甲".repeat(600);
        String longResult = "结果头" + "乙".repeat(5000);
        SequencedLlmService llm = new SequencedLlmService(
                "Thought:用超长参数调工具\nAction:echo\nAction Input:" + longArguments,
                "Thought:收尾\nFinal Answer:长内容处理完成");
        Harness harness = harness(llm, new FixedResultTool(longResult));

        harness.executor().run(SESSION_ID, USER_ID, USER_MESSAGE, AgentExecutionMode.REACT);

        // 参数摘要和结果都按原文硬截断：300 / 2000，不做省略号也不调模型压缩
        ConversationMessageRecord callRecord = harness.messages().get(1);
        ConversationMessageRecord resultRecord = harness.messages().get(2);
        assertThat(callRecord.getRole()).isEqualTo("assistant");
        assertThat(callRecord.getContent()).isEmpty();
        assertThat(callRecord.getToolArguments()).isEqualTo(longArguments.substring(0, 300)).hasSize(300);
        assertThat(resultRecord.getRole()).isEqualTo("tool");
        assertThat(resultRecord.getContent()).isEqualTo(longResult.substring(0, 2000)).hasSize(2000);
        assertThat(resultRecord.getToolCallId()).isEqualTo(callRecord.getToolCallId());

        // 短期记忆里的调用/结果与消息表一致，渲染出的是截断后的参数
        List<MemoryMessage> recent = harness.memory().getRecentMessages(SESSION_ID);
        assertThat(recent.get(1).promptText())
                .isEqualTo("ToolCall: echo(" + longArguments.substring(0, 300) + ")");
        assertThat(recent.get(2).content()).hasSize(2000);
        assertThat(recent.get(2).toolCallId()).isEqualTo(recent.get(1).toolCallId());

        // t_agent_step 仍保存完整原始入参/出参，截断只发生在会话历史
        AgentStepRecord actionStep = harness.traceSteps().get(0);
        assertThat(actionStep.getToolInput()).isEqualTo(longArguments).hasSize(602);
        assertThat(actionStep.getToolOutput()).isEqualTo(longResult).hasSize(5003);
    }

    @Test
    void shouldSkipStepsWithoutActionWhenPersistingToolMessages() {
        // 第一轮没有 ReAct 标记（格式错误），第二轮只有 Thought 没有 Action，两轮都不算工具步骤
        SequencedLlmService llm = new SequencedLlmService(
                "我直接给出一个没有 ReAct 标记的回答",
                "Thought:我只思考但不调用工具",
                "Thought:已经恢复格式\nFinal Answer:补格式后的回答");
        Harness harness = harness(llm, new EchoTool());

        AgentChatResponse response = harness.executor().run(SESSION_ID, USER_ID, USER_MESSAGE,
                AgentExecutionMode.REACT);

        assertThat(response.finalAnswer()).isEqualTo("补格式后的回答");
        assertThat(response.steps()).hasSize(2).allMatch(step -> step.action() == null);

        // 非工具步骤只体现在步骤追踪里，不产生任何 assistant 工具调用行/tool 结果行
        assertThat(harness.memory().getRecentMessages(SESSION_ID))
                .extracting(MemoryMessage::role).containsExactly("Human", "AI");
        assertThat(harness.memory().getRecentMessages(SESSION_ID))
                .noneMatch(MemoryMessage::isToolCall)
                .noneMatch(MemoryMessage::isToolResult);
        assertThat(harness.messages()).extracting(ConversationMessageRecord::getRole)
                .containsExactly("user", "assistant");
        assertThat(harness.messages()).allMatch(message -> message.getToolCallId() == null);
        assertThat(harness.traceSteps()).extracting(AgentStepRecord::getStepType)
                .containsExactly("observation", "thought", "final");
        assertThat(harness.traceSteps()).allMatch(step -> step.getToolName() == null);
    }

    @Test
    void shouldPersistOnlyToolStepsFromPlannedResult() {
        Harness harness = harness(new SequencedLlmService(
                "Thought:规划模式不调用文本 ReAct\nFinal Answer:不应被使用"), new EchoTool());
        List<AgentStep> plannedSteps = List.of(
                new AgentStep(1, "执行取证", "echo", "计划参数", "计划结果"),
                // 计划步骤里可能混入没有 action 的收尾/说明步骤，不能写成工具消息
                new AgentStep(2, "信息已经足够", null, null, null),
                new AgentStep(3, "空动作步骤", "   ", "  ", null));
        when(harness.planAgent().run(anyString(), anyString()))
                .thenReturn(AgentRunResult.planExecute("计划最终答案", null, plannedSteps, null));

        AgentChatResponse response = harness.executor().run(SESSION_ID, USER_ID, USER_MESSAGE,
                AgentExecutionMode.PLAN_EXECUTE);

        assertThat(response.finalAnswer()).isEqualTo("计划最终答案");
        List<MemoryMessage> recent = harness.memory().getRecentMessages(SESSION_ID);
        assertThat(recent).extracting(MemoryMessage::role).containsExactly("Human", "AI", "Tool", "AI");
        assertThat(recent.get(1).isToolCall()).isTrue();
        assertThat(recent.get(1).toolArguments()).isEqualTo("计划参数");
        assertThat(recent.get(1).promptText()).isEqualTo("ToolCall: echo(计划参数)");
        assertThat(recent.get(2).isToolResult()).isTrue();
        assertThat(recent.get(2).content()).isEqualTo("计划结果");
        assertThat(recent.get(2).toolCallId()).isEqualTo(recent.get(1).toolCallId());
        assertThat(recent.get(3).content()).isEqualTo("计划最终答案");

        assertThat(harness.messages()).extracting(ConversationMessageRecord::getRole)
                .containsExactly("user", "assistant", "tool", "assistant");
        assertThat(harness.messages().get(1).getContent()).isEmpty();
        assertThat(harness.messages().get(1).getToolArguments()).isEqualTo("计划参数");
        assertThat(harness.messages().get(2).getToolCallId())
                .isEqualTo(harness.messages().get(1).getToolCallId());
    }

    @Test
    void shouldPersistToolCallPairsFromStructuredKernelLoop() {
        // 结构化内核每步返回 ReActStep，steps 列表同样要传给 persistChatTurn 写成成对消息
        SequencedStructuredLlmService llm = new SequencedStructuredLlmService(
                new ReActStep("结构化思考一", "echo", "结构化参数甲", null),
                new ReActStep("结构化思考二", "echo", "结构化参数乙", null),
                new ReActStep("信息足够了", null, null, "结构化最终回答"));
        Harness harness = harness(llm, new EchoTool(), true);

        AgentChatResponse response = harness.executor().run(SESSION_ID, USER_ID, USER_MESSAGE,
                AgentExecutionMode.REACT);

        assertThat(response.finalAnswer()).isEqualTo("结构化最终回答");
        List<MemoryMessage> recent = harness.memory().getRecentMessages(SESSION_ID);
        assertThat(recent).extracting(MemoryMessage::role)
                .containsExactly("Human", "AI", "Tool", "AI", "Tool", "AI");
        assertThat(recent.get(1).toolArguments()).isEqualTo("结构化参数甲");
        assertThat(recent.get(2).content()).isEqualTo("echo:结构化参数甲");
        assertThat(recent.get(3).toolArguments()).isEqualTo("结构化参数乙");
        assertThat(recent.get(4).content()).isEqualTo("echo:结构化参数乙");
        assertThat(recent.get(3).toolCallId()).isNotEqualTo(recent.get(1).toolCallId());
        assertThat(harness.messages()).extracting(ConversationMessageRecord::getRole)
                .containsExactly("user", "assistant", "tool", "assistant", "tool", "assistant");
        assertThat(harness.messages()).extracting(ConversationMessageRecord::getContent)
                .containsExactly(USER_MESSAGE, "", "echo:结构化参数甲", "", "echo:结构化参数乙", "结构化最终回答");
    }

    @Test
    void shouldCollectToolStepsFromStreamingTextLoop() {
        // 流式链路没有单独的 steps 列表，工具步骤由 persistAgentStep 收进 persistenceContext.toolSteps
        SequencedLlmService llm = new SequencedLlmService(
                "Thought:流式查评论\nAction:echo\nAction Input:流式参数",
                "Thought:流式收尾\nFinal Answer:流式回答");
        Harness harness = harness(llm, new EchoTool());

        harness.executor().runStreaming(SESSION_ID, USER_ID, USER_MESSAGE,
                AgentExecutionMode.REACT, new SseEmitter());

        List<MemoryMessage> recent = harness.memory().getRecentMessages(SESSION_ID);
        assertThat(recent).extracting(MemoryMessage::role).containsExactly("Human", "AI", "Tool", "AI");
        assertThat(recent.get(1).isToolCall()).isTrue();
        assertThat(recent.get(1).toolArguments()).isEqualTo("流式参数");
        assertThat(recent.get(2).isToolResult()).isTrue();
        assertThat(recent.get(2).content()).isEqualTo("echo:流式参数");
        assertThat(recent.get(2).toolCallId()).isEqualTo(recent.get(1).toolCallId());
        assertThat(recent.get(3).content()).isEqualTo("流式回答");

        assertThat(harness.messages()).extracting(ConversationMessageRecord::getRole)
                .containsExactly("user", "assistant", "tool", "assistant");
        assertThat(harness.messages().get(1).getToolName()).isEqualTo("echo");
        assertThat(harness.messages().get(1).getToolArguments()).isEqualTo("流式参数");
        assertThat(harness.messages().get(2).getToolCallId())
                .isEqualTo(harness.messages().get(1).getToolCallId());
    }

    @Test
    void shouldKeepOnlyUserMessageWhenTurnIsCancelledMidway() {
        // 客户端在工具执行完成后断开：这一轮不算完成，消息表与短期记忆只保留用户消息，
        // 工具步骤只留在可观测的 t_agent_step 里（设计文档 C9 / D10）。
        DisconnectableSseEmitter emitter = new DisconnectableSseEmitter();
        DisconnectingLlmService llm = new DisconnectingLlmService(emitter);
        Harness harness = harness(llm, new EchoTool());

        harness.executor().runStreaming(SESSION_ID, USER_ID, USER_MESSAGE,
                AgentExecutionMode.REACT, emitter);

        assertThat(llm.callCount).isEqualTo(2);
        assertThat(harness.memory().getRecentMessages(SESSION_ID))
                .extracting(MemoryMessage::role, MemoryMessage::content)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Human", USER_MESSAGE));
        assertThat(harness.messages()).isEmpty();
        // trace 是旁路可观测数据，取消的一轮也照常记录；工具消息不因此漏进会话历史
        assertThat(harness.traceSteps()).extracting(AgentStepRecord::getStepType)
                .containsExactly("action", "final");
        verify(harness.coordinator(), never()).afterTurn(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void shouldTruncateToolNameToColumnWidthInBothProtocolMessages() {
        String longToolName = "collect_" + "a".repeat(80);
        SequencedLlmService llm = new SequencedLlmService(
                "Thought:调用超长名字工具\nAction:" + longToolName + "\nAction Input:参数",
                "Thought:完成\nFinal Answer:完成");
        Harness harness = harness(llm, new EchoTool(longToolName));

        harness.executor().run(SESSION_ID, USER_ID, USER_MESSAGE, AgentExecutionMode.REACT);

        String truncatedName = longToolName.substring(0, 64);
        ConversationMessageRecord callRecord = harness.messages().get(1);
        ConversationMessageRecord resultRecord = harness.messages().get(2);
        // tool_name 列宽 64：调用行和结果行都按列宽截断，参数/结果列不受影响
        assertThat(callRecord.getToolName()).isEqualTo(truncatedName);
        assertThat(resultRecord.getToolName()).isEqualTo(truncatedName);
        assertThat(callRecord.getToolArguments()).isEqualTo("参数");
        assertThat(resultRecord.getContent()).isEqualTo("echo:参数");
        // 短期记忆里的配对消息也是截断后的工具名，渲染 promptText 使用它
        List<MemoryMessage> recent = harness.memory().getRecentMessages(SESSION_ID);
        assertThat(recent.get(1).toolName()).isEqualTo(truncatedName);
        assertThat(recent.get(1).promptText()).isEqualTo("ToolCall: " + truncatedName + "(参数)");
        assertThat(recent.get(2).toolName()).isEqualTo(truncatedName);
    }

    private Harness harness(LLMService llm, Tool tool) {
        return harness(llm, tool, false);
    }

    /**
     * @param structuredKernelEnabled true 时走结构化 ReAct，false 时走文本 ReAct；
     *                               两条路径共用同一个 persistChatTurn。
     */
    private Harness harness(LLMService llm, Tool tool, boolean structuredKernelEnabled) {
        ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());
        SummaryMemory summary = new SummaryMemory(
                new SummaryMemoryProperties(false, 256_000, 2),
                prompt -> new ChatResponse(List.of()), new StubPromptService());
        LongTermMemory longTerm = mock(LongTermMemory.class);
        when(longTerm.retrieveForContext(anyString(), anyString())).thenReturn(List.of());
        LongTermMemoryExtractionCoordinator coordinator = mock(LongTermMemoryExtractionCoordinator.class);
        PlanAndExecuteAgent planAgent = mock(PlanAndExecuteAgent.class);

        List<ConversationMessageRecord> messages = new ArrayList<>();
        AtomicLong messageIds = new AtomicLong();
        ConversationSessionMapper conversations = mock(ConversationSessionMapper.class);
        doAnswer(invocation -> {
            ConversationMessageRecord message = invocation.getArgument(0);
            message.setId(messageIds.incrementAndGet());
            messages.add(message);
            return 1;
        }).when(conversations).insertMessage(any(ConversationMessageRecord.class));

        List<AgentStepRecord> traceSteps = new ArrayList<>();
        AgentTraceMapper traceMapper = mock(AgentTraceMapper.class);
        when(traceMapper.insertTrace(any(AgentTraceRecord.class))).thenReturn(1);
        doAnswer(invocation -> {
            traceSteps.add(invocation.getArgument(0));
            return 1;
        }).when(traceMapper).insertStep(any(AgentStepRecord.class));

        ToolRegistry registry = new ToolRegistry(List.of(tool));
        registry.init();
        RuntimeSettingService settings = mock(RuntimeSettingService.class);
        when(settings.isStructuredKernelEnabled()).thenReturn(structuredKernelEnabled);
        AgentExecutor executor = new AgentExecutor(
                llm, registry, new ToolExecutor(registry, new ToolExecutionProperties(10, 0)),
                memory, summary, longTerm, null, new StubPromptService(),
                settings, null, planAgent, null,
                traceMapper, conversations, null, null, null, coordinator);
        return new Harness(executor, memory, messages, traceSteps, coordinator, planAgent);
    }

    private record Harness(AgentExecutor executor, ShortTermMemory memory,
                           List<ConversationMessageRecord> messages,
                           List<AgentStepRecord> traceSteps,
                           LongTermMemoryExtractionCoordinator coordinator,
                           PlanAndExecuteAgent planAgent) {
    }

    /** 可按测试时机模拟「客户端断开」的 SSE 发射器，用于触发取消分支。 */
    private static class DisconnectableSseEmitter extends SseEmitter {

        private Consumer<Throwable> errorCallback;

        @Override
        public void onError(Consumer<Throwable> callback) {
            this.errorCallback = callback;
            super.onError(callback);
        }

        void simulateDisconnect() {
            if (errorCallback != null) {
                errorCallback.accept(new IOException("客户端已断开"));
            }
        }
    }

    /** 第一次返回工具调用、第二次返回最终答案，并在第二次调用时模拟客户端断开。 */
    private static class DisconnectingLlmService extends LLMService {

        private final DisconnectableSseEmitter emitter;
        private int callCount;

        DisconnectingLlmService(DisconnectableSseEmitter emitter) {
            super();
            this.emitter = emitter;
        }

        @Override
        public LlmCallResult chatWithUsage(String systemPrompt, String userMessage) {
            callCount++;
            if (callCount == 2) {
                emitter.simulateDisconnect();
            }
            String response = callCount == 1
                    ? "Thought:先查评论\nAction:echo\nAction Input:断线参数"
                    : "Thought:收尾\nFinal Answer:断线回答";
            return new LlmCallResult(response, "test-model", 2, 4, 8, 1L);
        }
    }

    /** 按调用顺序返回预置 ReActStep，用于覆盖结构化内核路径。 */
    private static class SequencedStructuredLlmService extends LLMService {

        private final List<ReActStep> responses;
        private int callCount;

        SequencedStructuredLlmService(ReActStep... responses) {
            super();
            this.responses = List.of(responses);
        }

        @Override
        public <T> StructuredCallResult<T> chatStructuredWithUsage(String systemPrompt, String userMessage,
                                                                   Class<T> type) {
            ReActStep response = responses.get(Math.min(callCount, responses.size() - 1));
            callCount++;
            return new StructuredCallResult<>(type.cast(response), 2, 4, 8, 1L);
        }
    }

    /** 按调用顺序返回预置文本；超出预置数量时复用最后一条，避免用例因多调用一次而越界。 */
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
            return new LlmCallResult(chat(systemPrompt, userMessage), "test-model", 2, 4, 8, 1L);
        }
    }

    private static class EchoTool implements Tool {

        private final String name;

        EchoTool() {
            this("echo");
        }

        EchoTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "返回 echo:参数";
        }

        @Override
        public String execute(String input) {
            return "echo:" + input;
        }
    }

    /** 返回固定长结果，用于验证会话历史里的结果截断。 */
    private static class FixedResultTool implements Tool {

        private final String result;

        FixedResultTool(String result) {
            this.result = result;
        }

        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public String getDescription() {
            return "返回固定结果";
        }

        @Override
        public String execute(String input) {
            return result;
        }
    }
}
