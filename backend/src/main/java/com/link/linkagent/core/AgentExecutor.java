package com.link.linkagent.core;

import com.link.linkagent.dto.AgentChatResponse;
import com.link.linkagent.llm.ChatReplayContext;
import com.link.linkagent.llm.ChatRequestEnvelope;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.LlmCallResult;
import com.link.linkagent.llm.StrictToolCall;
import com.link.linkagent.llm.StructuredCallResult;
import com.link.linkagent.llm.TokenCounter;
import com.link.linkagent.llm.ToolCallingCallResult;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.memory.AgentTraceMapper;
import com.link.linkagent.memory.ConversationSessionMapper;
import com.link.linkagent.memory.LongTermMemory;
import com.link.linkagent.core.multi.MultiAgentOrchestrator;
import com.link.linkagent.core.plan.PlanAndExecuteAgent;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.memory.LongTermMemoryExtractionCoordinator;
import com.link.linkagent.memory.LongTermMemoryExtractor;
import com.link.linkagent.memory.LongTermMemoryRecord;
import com.link.linkagent.memory.MemoryMessage;
import com.link.linkagent.memory.MemoryObservationContext;
import com.link.linkagent.memory.ShortTermMemory;
import com.link.linkagent.memory.SummaryFactExtractionResult;
import com.link.linkagent.memory.SummaryFactExtractor;
import com.link.linkagent.memory.SummaryFactPoint;
import com.link.linkagent.memory.SummaryMemory;
import com.link.linkagent.memory.SummaryReplayPrefix;
import com.link.linkagent.memory.model.AgentStepRecord;
import com.link.linkagent.memory.model.AgentTraceRecord;
import com.link.linkagent.memory.model.ConversationMessageRecord;
import com.link.linkagent.memory.model.ConversationSessionRecord;
import com.link.linkagent.tool.Tool;
import com.link.linkagent.tool.ToolExecutor;
import com.link.linkagent.tool.ToolRegistry;
import com.link.linkagent.tool.ToolResultCompressor;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.util.TextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/**
 * ReAct 主循环 —— 驱动 Thought → Action → Observation 迭代。
 * <p>
 * 核心设计：默认结构化 ReAct，每步由 schema 约束的 {@link ReActStep} 承载；文本 ReAct 保留为运行期可切换的兜底路径。
 */
@Component
public class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ShortTermMemory shortTermMemory;
    private final SummaryMemory summaryMemory;
    private final LongTermMemory longTermMemory;
    /**
     * 长期记忆提取协调器：预算累加器 + 游标 + 批量抽取都在它里面（设计文档 C2 / C4），
     * AgentExecutor 只负责在「一轮对话结束」和「压缩完成」两个时点通知它。
     */
    private final LongTermMemoryExtractionCoordinator longTermMemoryExtractionCoordinator;
    private final PromptService promptService;
    private final RuntimeSettingService runtimeSettingService;
    private final AgentExecutionModeRouter executionModeRouter;
    private final PlanAndExecuteAgent planAndExecuteAgent;
    private final MultiAgentOrchestrator multiAgentOrchestrator;
    private final AgentTraceMapper agentTraceMapper;
    private final ConversationSessionMapper conversationSessionMapper;

    /** 本地 token 计量器；单测构造器不注入时为 null，此时跳过压缩判断。 */
    private final TokenCounter tokenCounter;

    /** 工具结果体积控制；单测构造器不注入时为 null，此时工具结果原样进上下文。 */
    private final ToolResultCompressor toolResultCompressor;

    /** 压缩时的事实点抽取器；单测构造器不注入时为 null，此时压缩不写长期记忆。 */
    private final SummaryFactExtractor summaryFactExtractor;
    /** 生产环境从运行期设置读取结构化开关；单测不注入设置服务时回退到构造器默认值。 */
    private final boolean structuredKernelDefaultEnabled;

    /**
     * 每个会话最近一次真实请求的「可复用信封」（2026-10，DSH 式缓存优化）。
     * <p>
     * 摘要压缩发生在下一轮请求组装时，这时上一轮真实请求的 prompt 还留在 DeepSeek 前缀缓存里。
     * 只记 system/对话文本不够：供应商渲染前缀时还会带上 tools、thinking 等请求形状，任何一处不同都会让
     * 25 万 token 在正文之前分叉。所以这里存的是 LLMService 实际发出的完整请求信封，
     * 压缩时用它原样重放、只在末尾追加指令。没记到（评测首次压缩、进程刚重启）时退回冷启动 prompt。
     */
    private final Map<String, LastRequest> lastRequestPrefixes = new ConcurrentHashMap<>();

    /**
     * 上次压缩失败时的上下文 token 数（按会话）。
     * <p>
     * 压缩失败后原文仍保留，下一轮只多几千 token 就再次触发压缩，会在阈值附近连续烧「生成 + 复核」；
     * 记下失败点，等上下文再涨 {@code retry-token-delta} 才允许重试，把失败重试从每轮一次降到隔几轮一次。
     * 成功压缩后清除记录。
     */
    private final Map<String, Integer> compactionFailureTokens = new ConcurrentHashMap<>();

    /**
     * 一次真实请求的可复用信封。
     *
     * @param envelope 真实请求的出口、请求形状与当时的消息列表
     * @param summary  当次请求里摘要块的正文，用来验证信封没有过期
     * @param history  当次上下文实际包含的历史，用来核对本次待压缩消息是否已在信封中
     */
    private record LastRequest(ChatRequestEnvelope envelope, String summary, List<MemoryMessage> history) {
    }

    /**
     * 最多保留多少个会话的请求信封。
     * <p>
     * 信封里带着上一轮完整请求消息（长会话可达几十万 token 的文本），不能无限存。
     * 压缩永远只用到「当前会话最近一轮」，超出上限时淘汰任意旧会话即可，淘汰只影响缓存命中、不影响正确性。
     */
    private static final int MAX_CACHED_REPLAY_SESSIONS = 8;

    /**
     * ReAct 最大迭代次数。防止 LLM 陷入死循环或工具调用递归耗尽资源（Token 成本 + CPU 时间）。
     * 10 次对大多数任务够用——常规问题 2~4 轮解决，复杂多步推理很少超过 8 轮。
     */
    private static final int MAX_ITERATIONS = 10;

    /**
     * 写进会话历史的工具参数摘要上限（字符数）。
     * <p>
     * 只保留定位一次调用所需的信息；完整参数仍在 t_agent_step.tool_input 里，需要回查时去那里拿。
     * 不设上限时，一次带长文本参数的调用就能把整轮历史撑大，和「按 token 压缩」的计量口径对不上。
     */
    private static final int TOOL_MESSAGE_ARGUMENT_MAX_CHARS = 300;

    /**
     * 写进会话历史的工具结果上限（字符数）。
     * <p>
     * 和 {@link ToolResultCompressor} 的 2000 字符压缩阈值同口径：存储侧只做原文截断，不再额外调模型压缩，
     * 避免每个工具步骤在持久化时多花一次模型调用；当轮模型看到的仍是压缩器给出的版本（≤6000 字）。
     */
    private static final int TOOL_MESSAGE_RESULT_MAX_CHARS = 2000;
    /**
     * 格式错误时的标准 Observation 文本。当 LLM 未按 Thought/Action/Action Input 格式输出时，
     * 将此文本作为 Observation 回灌到对话中，让 LLM 看到错误后自行修正下一步输出。
     */
    private static final String FORMAT_ERROR_OBSERVATION = """
            LLM 本轮输出存在 ReAct 文本格式错误，已回传原始输出并要求模型重试。
            调用工具时必须输出：Thought: ...、Action: 工具名、Action Input: 参数。
            结束任务时必须输出：Final Answer: 最终内容。即使最终内容是 JSON，也不能省略 Final Answer: 前缀。
            """.trim();

    /**
     * 匹配 "Final Answer: 正文" 整行，捕获冒号后的所有文本（跨行）。
     * Pattern.DOTALL: . 匹配换行符，保证多行答案被完整捕获。
     */
    private static final Pattern FINAL_ANSWER = Pattern.compile(
            "Final Answer:\\s*(.*)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * 匹配 "Action: tool_name"，捕获工具名（字母/数字/下划线）。
     */
    private static final Pattern ACTION = Pattern.compile(
            "Action:\\s*(\\w+)", Pattern.CASE_INSENSITIVE);

    /**
     * 匹配 "Action Input: 参数"，捕获到行尾的第一个非贪婪内容。
     * .+? 非贪婪 → 只拿第一个参数值，不跨行。
     */
    private static final Pattern ACTION_INPUT = Pattern.compile(
            "Action Input:\\s*(.+?)(?:\\n|$)", Pattern.CASE_INSENSITIVE);

    /**
     * 匹配 "Thought: 思考内容"，捕获本行冒号后的文本。
     */
    private static final Pattern THOUGHT = Pattern.compile(
            "Thought:\\s*(.*?)(?:\\n|$)", Pattern.CASE_INSENSITIVE);

    @Autowired
    public AgentExecutor(LLMService llmService, ToolRegistry toolRegistry, ToolExecutor toolExecutor,
                         ShortTermMemory shortTermMemory,
                         SummaryMemory summaryMemory, LongTermMemory longTermMemory,
                         LongTermMemoryExtractor longTermMemoryExtractor,
                         PromptService promptService,
                         RuntimeSettingService runtimeSettingService,
                         AgentExecutionModeRouter executionModeRouter,
                         PlanAndExecuteAgent planAndExecuteAgent,
                         MultiAgentOrchestrator multiAgentOrchestrator,
                         AgentTraceMapper agentTraceMapper,
                         ConversationSessionMapper conversationSessionMapper,
                         TokenCounter tokenCounter,
                         ToolResultCompressor toolResultCompressor,
                         SummaryFactExtractor summaryFactExtractor,
                         LongTermMemoryExtractionCoordinator longTermMemoryExtractionCoordinator) {
        this(llmService, toolRegistry, toolExecutor, shortTermMemory, summaryMemory, longTermMemory,
                longTermMemoryExtractionCoordinator, promptService, runtimeSettingService, true,
                executionModeRouter, planAndExecuteAgent, multiAgentOrchestrator,
                agentTraceMapper, conversationSessionMapper, tokenCounter, toolResultCompressor,
                summaryFactExtractor);
    }

    public AgentExecutor(LLMService llmService, ToolRegistry toolRegistry, ToolExecutor toolExecutor,
                         ShortTermMemory shortTermMemory,
                         SummaryMemory summaryMemory, LongTermMemory longTermMemory,
                         LongTermMemoryExtractor longTermMemoryExtractor,
                         PromptService promptService) {
        this(llmService, toolRegistry, toolExecutor, shortTermMemory, summaryMemory, longTermMemory,
                new LongTermMemoryExtractionCoordinator(longTermMemoryExtractor, longTermMemory),
                promptService, null, false,
                new AgentExecutionModeRouter(), null, null, null, null, null, null, null);
    }

    AgentExecutor(LLMService llmService, ToolRegistry toolRegistry, ToolExecutor toolExecutor,
                  ShortTermMemory shortTermMemory,
                  SummaryMemory summaryMemory, LongTermMemory longTermMemory,
                  LongTermMemoryExtractor longTermMemoryExtractor,
                  PromptService promptService,
                  boolean structuredKernelDefaultEnabled) {
        this(llmService, toolRegistry, toolExecutor, shortTermMemory, summaryMemory, longTermMemory,
                new LongTermMemoryExtractionCoordinator(longTermMemoryExtractor, longTermMemory),
                promptService, null, structuredKernelDefaultEnabled,
                new AgentExecutionModeRouter(), null, null, null, null, null, null, null);
    }

    private AgentExecutor(LLMService llmService, ToolRegistry toolRegistry, ToolExecutor toolExecutor,
                          ShortTermMemory shortTermMemory,
                          SummaryMemory summaryMemory, LongTermMemory longTermMemory,
                          LongTermMemoryExtractionCoordinator longTermMemoryExtractionCoordinator,
                          PromptService promptService,
                          RuntimeSettingService runtimeSettingService,
                          boolean structuredKernelDefaultEnabled,
                          AgentExecutionModeRouter executionModeRouter,
                          PlanAndExecuteAgent planAndExecuteAgent,
                          MultiAgentOrchestrator multiAgentOrchestrator,
                          AgentTraceMapper agentTraceMapper,
                          ConversationSessionMapper conversationSessionMapper,
                          TokenCounter tokenCounter,
                          ToolResultCompressor toolResultCompressor,
                          SummaryFactExtractor summaryFactExtractor) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.shortTermMemory = shortTermMemory;
        this.summaryMemory = summaryMemory;
        this.longTermMemory = longTermMemory;
        this.longTermMemoryExtractionCoordinator = longTermMemoryExtractionCoordinator;
        this.promptService = promptService;
        this.runtimeSettingService = runtimeSettingService;
        this.structuredKernelDefaultEnabled = structuredKernelDefaultEnabled;
        this.executionModeRouter = executionModeRouter == null ? new AgentExecutionModeRouter() : executionModeRouter;
        this.planAndExecuteAgent = planAndExecuteAgent;
        this.multiAgentOrchestrator = multiAgentOrchestrator;
        this.agentTraceMapper = agentTraceMapper;
        this.conversationSessionMapper = conversationSessionMapper;
        this.tokenCounter = tokenCounter;
        this.toolResultCompressor = toolResultCompressor;
        this.summaryFactExtractor = summaryFactExtractor;
    }

    /**
     * ReAct 循环入口（无感模式），使用默认 userId 并以 AUTO 模式路由。
     * <p>
     * 此重载专为最简调用场景设计：调用方只需提供 sessionId 和用户消息即可，
     * Agent 自动决定走 REACT/PLAN_EXECUTE/MULTI_AGENT 中的最佳路径。
     *
     * @param sessionId 会话标识，用于关联短期/摘要记忆；为空时自动生成 UUID
     * @param userMessage 用户原始输入
     * @return 最终答案 + 步骤追踪（文本路 / 结构化路 / 规划模式统一包装为 AgentChatResponse）
     */
    public AgentChatResponse run(String sessionId, String userMessage) {
        return run(sessionId, "default", userMessage);
    }

    /**
     * ReAct 循环入口（指定 userId），以 AUTO 模式路由。
     *
     * @param sessionId 会话标识
     * @param userId 用户标识，用于长期记忆的存取隔离
     * @param userMessage 用户原始输入
     * @return 最终答案 + 步骤追踪
     */
    public AgentChatResponse run(String sessionId, String userId, String userMessage) {
        return run(sessionId, userId, userMessage, AgentExecutionMode.AUTO);
    }

    /**
     * ReAct 循环入口（指定 userId + 请求模式），是所有 run 重载的最终汇聚点。
     * <p>
     * 执行流程分为四个阶段：
     * <ol>
     *   <li><b>记忆拼接</b>：按"长期记忆 → 摘要记忆 → 短期记忆 → 用户输入"顺序拼接上下文，
     *       格式与系统提示词约定的对话模板一致。</li>
     *   <li><b>模式路由</b>：通过 {@link AgentExecutionModeRouter} 决定走 REACT / PLAN_EXECUTE / MULTI_AGENT。
     *       若计划模式（Plan & Execute / Multi-Agent）执行失败且请求模式为 AUTO，自动回退到 ReAct。</li>
     *   <li><b>内核选择</b>：结构化内核开 → 走 {@link #runStructuredLoop}（schema 约束的 JSON 每步）；
     *       结构化内核关 → 走文本路（自由文本 + 正则解析）。</li>
     *   <li><b>记忆持久化</b>：拿到 Final Answer 后，写入短期记忆 + 按需触发摘要 + 抽取长期记忆。</li>
     * </ol>
     *
     * @param sessionId 会话标识，为空时自动生成 UUID
     * @param userId 用户标识，为空时使用 "default"
     * @param userMessage 用户原始输入
     * @param requestedMode 调用方期望的执行模式（AUTO / REACT / PLAN_EXECUTE / MULTI_AGENT）
     * @return 最终答案 + 步骤追踪 + 可能的错误信息
     */

    public AgentChatResponse run(String sessionId, String userId, String userMessage, AgentExecutionMode requestedMode) {
        return run(sessionId,userId,userMessage,requestedMode,"");
    }

    /** 每轮平台快照只参与推理，持久化仍使用用户原话，避免把瞬时状态写成长期记忆。 */
    public AgentChatResponse run(String sessionId, String userId, String userMessage,
                                 AgentExecutionMode requestedMode, String requestContext) {
        requestContext=TextUtil.trimToDefault(requestContext, "");
        String resolvedSessionId = resolveSessionId(sessionId);
        String resolvedUserId = resolveUserId(userId);
        AgentPersistenceContext persistenceContext = startAgentTrace(resolvedSessionId, resolvedUserId, userMessage);
        // 采集本轮第一次真实请求的信封；压缩会发生在下一轮组装上下文时，届时用它重放前缀。
        // 计划执行模式成功时本函数会提前返回，那种轮次没有可复用的真实对话请求，作用域留到计划模式之后再开启。
        ChatReplayContext.Scope replayScope = null;
        List<MemoryMessage> replayHistory = List.of();

        try {
            // 拼接记忆 + 用户输入作为对话起点；token 超过阈值时在这里先压缩（设计文档 A2 / A5）
            ContextAssembly assembly = buildConversationContext(resolvedSessionId, resolvedUserId, userMessage);
            StringBuilder conversation = new StringBuilder(assembly.text());

            AgentExecutionMode selectedMode = executionModeRouter.route(requestedMode, userMessage);
            // 平台快照只在非 REACT 模式前置；它属于真实请求文本的一部分，压缩重放时由信封 + preamble 带上。
            if (selectedMode != AgentExecutionMode.REACT) conversation.insert(0, requestContext + "\n");
            AgentRunResult plannedResult = tryRunPlannedMode(selectedMode, requestedMode, conversation.toString(), userMessage);
            if (plannedResult != null) {
                if (TextUtil.hasText(plannedResult.finalAnswer())) {
                    persistChatTurn(resolvedSessionId, resolvedUserId, userMessage, plannedResult.finalAnswer(),
                            persistenceContext.totalTokens, plannedResult.steps());
                }
                completeAgentTrace(persistenceContext, 1, plannedResult.finalAnswer(), plannedResult.totalSteps(), null);
                return toChatResponse(resolvedSessionId, plannedResult);
            }

            // 从这里开始才是真正发对话请求的路径：开启采集作用域，记下本轮请求与当时的摘要版本。
            replayScope = ChatReplayContext.begin();
            replayScope.summary(assembly.summary());
            replayHistory = assembly.history();

            // === 结构化内核路径（阶段 5.4+） ===
            // 每步通过 chatStructured 产出受 JSON Schema 约束的 ReActStep，省去正则解析，降低格式错误率。
            // 开关由 RuntimeSettingService 控制，方便生产环境出现结构化输出异常时快速切回文本路兜底。
            if (isStructuredKernelEnabled()) {
                String structuredSystemPrompt = buildStructuredSystemPrompt(toolRegistry.getAllTools())
                        + (requestContext.isEmpty() ? "" : "\n\n"+requestContext);
                List<AgentStep> structuredSteps = new ArrayList<>();
                String structuredAnswer;
                if (llmService.isStrictFunctionCallingEnabled()) {
                    String toolCallingSystemPrompt = buildToolCallingSystemPrompt(toolRegistry.getAllTools())
                        + (requestContext.isEmpty() ? "" : "\n\n"+requestContext);
                    structuredAnswer = runStrictToolCallingLoop(
                            toolCallingSystemPrompt,
                            structuredSystemPrompt,
                            conversation,
                            structuredSteps,
                            persistenceContext
                    );
                } else {
                    structuredAnswer = runStructuredLoop(
                            structuredSystemPrompt, conversation, structuredSteps, persistenceContext
                    );
                }
                if (structuredAnswer == null) {
                    // 迭代次数超限：不持久化任何记忆，因为未拿到有效答案，避免脏数据污染记忆层
                    completeAgentTrace(persistenceContext, 2, null, structuredSteps.size(), "迭代次数超过上限");
                    return new AgentChatResponse(resolvedSessionId, null, "迭代次数超过上限", structuredSteps.size(), structuredSteps);
                }
                persistChatTurn(resolvedSessionId, resolvedUserId, userMessage, structuredAnswer,
                        persistenceContext.totalTokens, structuredSteps);
                completeAgentTrace(persistenceContext, 1, structuredAnswer, structuredSteps.size(), null);
                return new AgentChatResponse(resolvedSessionId, structuredAnswer, null, structuredSteps.size(), structuredSteps);
            }

            // === 文本路径（原 ReAct 文本解析循环，逻辑保持不变） ===
            // 走"自由文本 LLM 输出 → 正则提取 Thought/Action/Final Answer"的传统 ReAct 路径。
            // 兜底保障：即使结构化内核关闭或异常，核心 Agent 循环仍可正常工作。
            String systemPrompt = buildSystemPrompt(toolRegistry.getAllTools())
                        + (requestContext.isEmpty() ? "" : "\n\n"+requestContext);
            String finalAnswer = null;
            int iteration = 0;
            List<AgentStep> steps = new ArrayList<>();
            while(true){
                iteration++;

                // 迭代上限兜底：防止无限循环耗尽资源 (Token 费用 + CPU 时间 + 连接池)
                // 超过 MAX_ITERATIONS 说明 LLM 无法在给定轮次内完成推理，可能是任务本身不可解或提示词有缺陷
                if(iteration > MAX_ITERATIONS){
                    completeAgentTrace(persistenceContext, 2, finalAnswer, steps.size(), "迭代次数超过上限");
                    return new AgentChatResponse(resolvedSessionId, finalAnswer, "迭代次数超过上限", steps.size(), steps);
                }

                log.info("正在进行第{}轮ReAct迭代...", iteration);

                // 1. 调用 LLM，传入完整对话历史（含之前所有轮次的 Thought/Action/Observation）
                logContextBudget(systemPrompt, conversation);
            LlmCallResult llmResult = llmService.chatWithUsage(systemPrompt, conversation.toString());
                String llmAnswer = llmResult.content();
                int stepTokenCount = calculateTokenCount(llmResult.promptTokens(), llmResult.completionTokens(), llmResult.totalTokens());
                persistenceContext.totalTokens += stepTokenCount;
                // 2. 优先检测 Final Answer（即使同时存在 Action 也以 Final Answer 为准）
                // 设计权衡：Final Answer 优先级最高，因为它是推理链的最终产物。
                // 如果 LLM 同时输出了 Action 和 Final Answer，取 Final Answer 直接终止——宁可损失一步工具调用，
                // 也不让用户在答案已经生成的情况下继续等待多余的推理轮次。
                finalAnswer = parseFinalAnswer(llmAnswer);
                if (TextUtil.hasText(finalAnswer)) {
                    log.info("ReAct 第{}轮生成最终答案", iteration);
                    // Final Answer 拿到即持久化：短期记忆写 Human + AI 对、按需触发摘要、抽取长期记忆
                    persistAgentStep(persistenceContext, iteration, "final", finalAnswer, null, null, null, stepTokenCount);
                    persistChatTurn(resolvedSessionId, resolvedUserId, userMessage, finalAnswer,
                            persistenceContext.totalTokens, steps);
                    completeAgentTrace(persistenceContext, 1, finalAnswer, steps.size(), null);
                    return new AgentChatResponse(resolvedSessionId, finalAnswer, null, steps.size(), steps);
                }

                // 3. 提取 Thought — 必须存在，否则说明 LLM 未按提示词格式输出，需要回喂错误让 LLM 重试
                String thought = parseThought(llmAnswer);
                if (TextUtil.isBlank(thought)) {
                    // LLM 既未给出 Final Answer 也未给出合法 Thought：本轮输出完全不可用
                    log.warn("ReAct 第{}轮未解析到合法 Thought，已进入格式纠错", iteration);
                    // 记录本轮为格式错误步骤：Thought 设为错误描述，Observation 设为标准格式错误提示
                    // 这样在步骤追踪中可以看到 LLM 在哪一轮输出了不合法内容
                    steps.add(new AgentStep(iteration, "未解析到 Thought，模型可能没有按提示词要求输出。", null, null,
                            FORMAT_ERROR_OBSERVATION));
                    persistAgentStep(persistenceContext, iteration, "observation",
                            "未解析到 Thought，模型可能没有按提示词要求输出。",
                            null, null, FORMAT_ERROR_OBSERVATION, stepTokenCount);
                    appendFormatCorrection(conversation, llmAnswer);
                    continue;
                }
                log.info("ReAct 第{}轮完成思考", iteration);

                // 4. 尝试解析 Action + Action Input（两者必须同时存在才构成有效工具调用）
                // 设计权衡：Action 和 Action Input 分开提取但要求同时非空。
                // 若仅 Action 存在而 Action Input 缺失，说明 LLM 输出不完整——不应猜测参数默认值，
                // 因为给工具的默认值很可能不是 LLM 的意图，执行后产生错误 Observation 会误导后续推理。
                ToolCall action = parseAction(llmAnswer);
                if(action == null){
                    // LLM 既未给出 Final Answer 也未给出合法 Action：本轮输出不完整，回喂错误让 LLM 重试
                    log.warn("ReAct 第{}轮未解析到合法 Action，已进入格式纠错", iteration);
                    steps.add(new AgentStep(iteration, thought, null, null, null));
                    persistAgentStep(persistenceContext, iteration, "thought", thought, null, null, null, stepTokenCount);
                    appendFormatCorrection(conversation, llmAnswer);
                    continue;
                }
                log.info("ReAct 第{}轮准备调用工具，tool={}", iteration, action.name());

                // 5. 执行工具，得到 Observation
                // ToolExecutor 内部负责校验工具是否存在、参数是否合法，并捕获工具执行异常返回错误 Observation
                Observation observation = toolExecutor.execute(action);
                // 工具结果进上下文前先做体积控制（A7 / D18）：原文仍完整落 t_agent_step
                String boundedResult = boundedToolResult(observation.toolName(), observation.result());
                log.info("ReAct 第{}轮工具执行完成，tool={}", iteration, observation.toolName());
                steps.add(new AgentStep(iteration, thought, action.name(), action.arguments(), observation.result()));
                persistAgentStep(persistenceContext, iteration, "action", thought,
                        action.name(), action.arguments(), observation.result(), stepTokenCount);

                // 6. 将本轮的 Thought/Action/Observation 拼回对话，供下一轮 LLM 参考
                // 拼接格式与系统提示词约定的输出模板一致，形成完整的 ReAct 对话轨迹。
                // 注意：这里用原始 LLM 输出的 thought（而非正则提取后的），保留模型的完整思考语义。
                conversation.append("AI:\n").append("Thought:")
                        .append(thought).append("\n")
                        .append("Action:").append(action.name()).append("\n")
                        .append("Action Input:").append(action.arguments()).append("\n")
                        .append("Observation:")
                        .append(observation.toolName())
                        .append(":")
                        .append(boundedResult)
                        .append("\n\n");
            }
        } catch (RuntimeException exception) {
            // 主流程异常仍交给上层统一处理；这里先回填失败终态，避免 trace 长期停留在运行中。
            completeAgentTrace(persistenceContext, 2, null, persistenceContext.totalSteps,
                    exception.getMessage() != null ? exception.getMessage() : "未知错误");
            throw exception;
        } finally {
            // 把本轮第一次真实请求的信封留给下一轮压缩复用；异常轮也会保留（请求可能已经成功发出并进缓存）。
            storeReplayEnvelope(resolvedSessionId, replayScope, replayHistory);
        }
    }

    /**
     * 任务维度的一次性带工具推理入口，<b>不读写任何会话记忆</b>（短期 / 摘要 / 长期）。
     * <p>
     * 用于发布前优化等「先取证、再结构化合成」的内部场景：调用方传入一段自包含的任务说明，
     * Agent 自主决定调用哪些工具取证，最终以 Final Answer 形式产出一段自由文本结论。
     * <p>
     * 为什么不复用 {@link #run}：run 会把对话写进短期记忆、按需触发摘要、并在 Final Answer 时
     * 抽取长期记忆——那是「面向用户的聊天回合」的语义。而这里是任务内部的一次性中间步骤，
     * 若写进会话记忆会污染用户的 /agent/chat 历史，并触发无意义的长期记忆抽取（多余的 LLM 调用与垃圾数据）。
     * 因此单独承载，仅去掉记忆读写这一层；ReAct 解析、工具执行、系统提示词与 {@code ToolRegistry}
     * 全部与 run 共用（同一内核、同一工具生态）。
     * <p>
     * 为什么文本路是新增方法而非抽取共享循环：run 的文本循环已稳定驱动 /agent/chat，为零回归保持其字节级不动，
     * 故 run / runTask 的文本循环各保留一份。（5.4 的结构化路因新增 run + runTask 两个变体、越过 rule of three，
     * 已抽取共享的 {@link #runStructuredLoop}；文本路仍各自保留以守零回归。）
     *
     * @param taskMessage 自包含的任务说明（含所需材料与取证目标），作为 ReAct 循环的初始 Human 输入
     * @return ReAct 结果；finalAnswer 为自由文本结论，超出迭代上限时 error 非空、finalAnswer 可能为 null
     */
    public AgentChatResponse runTask(String taskMessage) {
        // === 结构化内核路径 ===
        // 与 run 的结构化路逻辑一致，但 sessionId 固定为 null、不加载/不持久化任何记忆。
        if (isStructuredKernelEnabled()) {
            String structuredSystemPrompt = buildStructuredSystemPrompt(toolRegistry.getAllTools());
            StringBuilder structuredConversation = new StringBuilder();
            structuredConversation.append("Human:").append(taskMessage).append("\n\n");
            List<AgentStep> structuredSteps = new ArrayList<>();
            String structuredAnswer;
            if (llmService.isStrictFunctionCallingEnabled()) {
                String toolCallingSystemPrompt = buildToolCallingSystemPrompt(toolRegistry.getAllTools());
                structuredAnswer = runStrictToolCallingLoop(
                        toolCallingSystemPrompt,
                        structuredSystemPrompt,
                        structuredConversation,
                        structuredSteps,
                        null
                );
            } else {
                structuredAnswer = runStructuredLoop(structuredSystemPrompt, structuredConversation, structuredSteps);
            }
            if (structuredAnswer == null) {
                return new AgentChatResponse(null, null, "迭代次数超过上限", structuredSteps.size(), structuredSteps);
            }
            return new AgentChatResponse(null, structuredAnswer, null, structuredSteps.size(), structuredSteps);
        }

        // === 文本路径 ===
        // 工具集与系统提示词复用 run 的同一套：RAG 开启时 getAllTools() 自带 knowledge_search
        String systemPrompt = buildSystemPrompt(toolRegistry.getAllTools());
        StringBuilder conversation = new StringBuilder();
        // 不加载任何记忆，仅以任务说明作为对话起点（这是与 run 方法的核心区别）
        conversation.append("Human:").append(taskMessage).append("\n\n");

        List<AgentStep> steps = new ArrayList<>();
        String finalAnswer = null;
        int iteration = 0;
        while (true) {
            iteration++;
            // 迭代上限兜底：与 run 一致，防止无限循环耗尽资源
            if (iteration > MAX_ITERATIONS) {
                return new AgentChatResponse(null, finalAnswer, "迭代次数超过上限", steps.size(), steps);
            }
            log.debug("任务模式 ReAct 第{}轮迭代", iteration);

            logContextBudget(systemPrompt, conversation);
            String llmAnswer = llmService.chat(systemPrompt, conversation.toString());

            // 优先检测 Final Answer：拿到自由文本结论即终止
            // 全程不写任何记忆（这是与 run 文本路的核心区别），因为任务推理是一次性中间步骤
            finalAnswer = parseFinalAnswer(llmAnswer);
            if (TextUtil.hasText(finalAnswer)) {
                log.info("任务模式 ReAct 第{}轮生成最终答案，本次共执行{}次工具调用", iteration, steps.size());
                return new AgentChatResponse(null, finalAnswer, null, steps.size(), steps);
            }

            String thought = parseThought(llmAnswer);
            if (TextUtil.isBlank(thought)) {
                // 既无 Final Answer 也无合法 Thought：回喂格式错误让 LLM 重试（与 run 处理一致）
                steps.add(new AgentStep(iteration, "未解析到 Thought，模型可能没有按提示词要求输出。", null, null,
                        FORMAT_ERROR_OBSERVATION));
                appendFormatCorrection(conversation, llmAnswer);
                continue;
            }

            ToolCall action = parseAction(llmAnswer);
            if (action == null) {
                // 无合法 Action：回喂格式错误让 LLM 重试
                steps.add(new AgentStep(iteration, thought, null, null, null));
                appendFormatCorrection(conversation, llmAnswer);
                continue;
            }

            // 只记录工具名，不记录 Action Input，避免把搜索词或任务材料重复写入日志。
            log.debug("任务模式 ReAct 第{}轮准备调用工具，tool={}", iteration, action.name());
            Observation observation = toolExecutor.execute(action);
            // 工具结果进上下文前先做体积控制（A7 / D18）：原文仍完整落 t_agent_step
            String boundedResult = boundedToolResult(observation.toolName(), observation.result());
            log.debug("任务模式 ReAct 第{}轮工具执行完成，tool={}", iteration, observation.toolName());
            steps.add(new AgentStep(iteration, thought, action.name(), action.arguments(), observation.result()));

            // 将本轮 Thought/Action/Observation 拼回对话，供下一轮参考（拼接格式与 run 一致）
            conversation.append("AI:\n").append("Thought:").append(thought).append("\n")
                    .append("Action:").append(action.name()).append("\n")
                    .append("Action Input:").append(action.arguments()).append("\n")
                    .append("Observation:").append(observation.toolName()).append(":")
                    .append(boundedResult).append("\n\n");
        }
    }

    /**
     * 任务推理入口（支持指定执行模式），用于发布前优化等内部场景需要显式控制模式的场合。
     * <p>
     * 路由策略：requestedMode 为 AUTO 时走路由器自动判定；为显式模式时原样传递。
     * 若路由结果为非 REACT 且编排器执行成功，直接返回编排器结果（不写记忆）；
     * 若编排器不为当前模式或执行失败，回退到 {@link #runTask(String)} 走 ReAct 核心循环。
     * <p>
     * 注意：sessionId 固定为 null（不关联会话），因为任务推理不读写记忆。
     *
     * @param taskMessage 自包含的任务说明
     * @param requestedMode 调用方期望的执行模式
     * @return ReAct/编排器结果
     */
    public AgentChatResponse runTask(String taskMessage, AgentExecutionMode requestedMode) {
        AgentExecutionMode selectedMode = executionModeRouter.route(requestedMode, taskMessage);
        String conversationContext = "Human:" + TextUtil.trimToDefault(taskMessage, "") + "\n\n";
        AgentRunResult plannedResult = tryRunPlannedMode(selectedMode, requestedMode, conversationContext, taskMessage);
        if (plannedResult != null) {
            return toChatResponse(null, plannedResult);
        }
        // 既有 runTask 默认语义保留为 ReAct，避免发布前优化内部取证链路被阶段 6 自动路由改变。
        return runTask(taskMessage);
    }

    /**
     * 尝试以计划模式（Plan & Execute / Multi-Agent）执行任务。
     * <p>
     * 仅在路由结果匹配对应模式且对应编排器已注入时才执行。若路由为 REACT 或编排器未注入，
     * 返回 null 表示"不干预"，由外层继续走 ReAct 主循环。
     * <p>
     * 异常处理策略（见 {@link #runPlannedSafely}）：AUTO 模式下异常被吞掉并回退 ReAct；
     * 显式指定模式时异常向上抛出，让调用方看到完整的失败堆栈便于排障。
     *
     * @param selectedMode 路由决策的模式（可能已被 AUTO 规则计算）
     * @param requestedMode 调用方原始请求的模式（区分 AUTO vs 显式指定）
     * @param conversationContext 拼接好记忆和用户消息的完整上下文字符串
     * @param userMessage 用户原始输入（传递给编排器用于内部逻辑）
     * @return 成功时返回编排器的执行结果；不应干预或失败时返回 null
     */
    private AgentRunResult tryRunPlannedMode(AgentExecutionMode selectedMode, AgentExecutionMode requestedMode,
                                             String conversationContext, String userMessage) {
        if (selectedMode == AgentExecutionMode.PLAN_EXECUTE && planAndExecuteAgent != null) {
            return runPlannedSafely(requestedMode, () -> planAndExecuteAgent.run(conversationContext, userMessage));
        }
        if (selectedMode == AgentExecutionMode.MULTI_AGENT && multiAgentOrchestrator != null) {
            return runPlannedSafely(requestedMode, () -> multiAgentOrchestrator.run(conversationContext, userMessage));
        }
        return null;
    }

    /**
     * 以安全方式执行规划模式编排器，统一处理模式回退策略。
     * <p>
     * 设计权衡：AUTO 模式优先"体验不中断"——规划链路异常时静默回退 ReAct；
     * 显式指定模式优先"排障透明"——异常直接向上抛出，让调用方看到完整失败信息。
     * 两种策略满足不同场景：AUTO 是面向终端用户的默认路径（不能因为路由失误就让用户看到 500），
     * 显式模式是面向开发者/调试场景（需要完整堆栈定位编排器内部问题）。
     *
     * @param requestedMode 调用方原始请求的模式
     * @param supplier 编排器的执行动作（懒加载，只在需要时执行）
     * @return 编排器执行结果；AUTO 模式下异常时返回 null 触发 ReAct 回退
     */
    private AgentRunResult runPlannedSafely(AgentExecutionMode requestedMode, Supplier<AgentRunResult> supplier) {
        try {
            AgentRunResult result = supplier.get();
            if (AgentExecutionMode.normalize(requestedMode) == AgentExecutionMode.AUTO
                    && result != null && result.totalSteps() == 0) {
                // 偏好确认、直接写稿等请求可能被关键词误判为规划任务；空计划不应让普通对话拒答。
                log.info("自动规划未产生执行步骤，回退 ReAct 完成当前请求。");
                return null;
            }
            return result;
        } catch (RuntimeException exception) {
            // AUTO 是体验优先：规划链路异常时回退 ReAct，避免用户只是问复杂问题就直接 500；显式指定模式时保留响亮失败，便于排障。
            if (AgentExecutionMode.normalize(requestedMode) == AgentExecutionMode.AUTO) {
                log.warn("自动规划模式执行失败，回退 ReAct。error={}", exception.getMessage(), exception);
                return null;
            }
            throw exception;
        }
    }

    /**
     * 将规划模式的 {@link AgentRunResult} 转换为统一的 {@link AgentChatResponse}。
     * <p>
     * 转换层意义：规划模式（Plan & Execute / Multi-Agent）产出的 AgentRunResult 字段比文本/结构化 ReAct 更丰富
     * （含 executionMode、planTrace、workerTraces 等编排特有字段），通过此方法统一映射到 AgentChatResponse，
     * 保证前端消费的响应结构一致。
     *
     * @param sessionId 会话标识；runTask 场景下为 null
     * @param result 规划模式编排器的执行结果
     * @return 统一的前端响应对象
     */
    private AgentChatResponse toChatResponse(String sessionId, AgentRunResult result) {
        return new AgentChatResponse(
                sessionId,
                result.finalAnswer(),
                result.stopReason(),
                result.totalSteps(),
                result.steps(),
                result.executionMode(),
                result.planTrace(),
                result.workerTraces()
        );
    }

    /**
     * 解析会话 ID：空白时自动生成 UUID，非空白时做 trim 去首尾空格。
     * <p>
     * 自动生成是面向新会话场景的默认行为；trim 是防御性处理——前端或网关可能误传带空格的 sessionId。
     */
    private String resolveSessionId(String sessionId) {
        if (TextUtil.isBlank(sessionId)) {
            return UUID.randomUUID().toString();
        }
        return sessionId.trim();
    }

    /**
     * 解析用户 ID：空白时回退 "default"，非空白时做 trim 去首尾空格。
     * <p>
     * "default" 作为匿名用户标识，保证长期记忆在未登录场景下仍可按用户维度存取。
     */
    private String resolveUserId(String userId) {
        if (TextUtil.isBlank(userId)) {
            return "default";
        }
        return userId.trim();
    }

    /**
     * 将长期记忆拼接到对话上下文中，格式为 "Long-term memory:" + 多行 " - key: content"。
     * <p>
     * 长期记忆放在对话最前面，让 LLM 优先参考用户的历史偏好和知识积累，再结合当前对话做推理。
     * 取多少条由检索侧的三维打分决定（设计文档 D1 / D11，top-K 默认 10）；
     * 输出顺序在步骤 9 会改成按稳定键重排（D12），这里保持检索分数顺序。
     */
    private void appendLongTermMemory(StringBuilder conversation, List<LongTermMemoryRecord> memories) {
        if (memories.isEmpty()) {
            return;
        }
        conversation.append("Long-term memory:\n");
        for (LongTermMemoryRecord memory : memories) {
            conversation.append("- ")
                    .append(memory.getMemoryKey())
                    .append(": ")
                    .append(memory.getContent())
                    .append("\n");
        }
        conversation.append("\n");
    }

    /**
     * 将短期记忆拼接到对话上下文中，格式为 "Recent conversation:" + 多行 "role:content"。
     * <p>
     * 短期记忆位于摘要之后、用户消息之前，为 LLM 提供最直接的对话连续性上下文。
     * 角色除 Human/AI 外还包含 {@code Tool}：每轮执行过的工具步骤以「调用+结果」一条消息落在这里，
     * 模型读取时的格式与 summary_memory 提示词里约定的 Tool 证据一致。
     * 不再按条数裁剪：只有请求 token 超过阈值、摘要成功生成后，才裁掉被摘要覆盖的旧消息
     * （见 buildConversationContext）。
     */
    private void appendMemory(StringBuilder conversation, List<MemoryMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        conversation.append("Recent conversation:\n");
        for (MemoryMessage message : messages) {
            // 工具调用消息正文为空，promptText 会渲染成 ToolCall: 工具(参数)；下一条 Tool 就是它的结果。
            conversation.append(MemoryObservationContext.prefix(message)).append(message.role())
                    .append(":")
                    .append(message.promptText())
                    .append("\n");
        }
        conversation.append("\n");
    }

    /** 生产路径：组装上下文时带上长期记忆，等价于 {@code buildConversationContext(..., true)}。 */
    private ContextAssembly buildConversationContext(String sessionId, String userId, String userMessage) {
        return buildConversationContext(sessionId, userId, userMessage, true);
    }

    /**
     * 组装一次请求的对话上下文，并在 token 超过阈值时先压缩（设计文档 A2 / A3 / A5）。
     *
     * <h3>为什么在这里判断</h3>
     * 计量对象是「组装好准备发出去的上下文」——长期记忆只有几百 token，真正会变大的是短期记忆
     * （不再按条数裁剪）与本轮用户输入。系统提示词与工具定义（合计约 300 token）不在这里计量：
     * 它们相对 256k 阈值可以忽略，且要等执行模式路由完成后才知道用哪一份提示词。
     *
     * <h3>压缩规则</h3>
     * 从尾部往前保留约 {@code retained-token-budget}（默认 16k）token 的整条消息，其余交给摘要承载；
     * 工具消息一条同时包含调用和结果，裁剪边界永远落在消息之间，不会把「调用+结果」拆开。
     * 摘要必须通过复核才裁剪原文，否则宁可这轮继续超阈值，也不能出现「原文已删、摘要没有」的空档
     * （设计文档 D4 / D6 / B4）。被压缩消息的原文不再保留，这是设计文档 A5 的明确取舍。
     *
     * <h3>事实点抽取（B2 / B3）</h3>
     * 摘要生成的同时并行抽取事实点（两者互不依赖，串行会让压缩那一轮多等一次模型调用），
     * 只有压缩真正成立时才把达到高分线的事实点写进长期记忆——否则原文还在短期记忆里，
     * 下一轮会重新抽取，同一批消息会重复入库。
     *
     * @param longTermMemoryEnabled 是否把长期记忆纳入本次组装。评测的摘要策略传 false：
     *                              只走「摘要 + 最近消息」，不检索长期记忆、不抽事实点、不推进游标，
     *                              否则压缩时抽到的事实点会在回答时被召回，把摘要的缺口补上。
     * @param sessionId   会话标识
     * @param userId      用户标识
     * @param userMessage 本轮用户输入
     * @return 组装（必要时已压缩）后的上下文文本
     */
    private ContextAssembly buildConversationContext(String sessionId, String userId, String userMessage,
                                                     boolean longTermMemoryEnabled) {
        List<MemoryMessage> recentMessages = shortTermMemory.getRecentMessages(sessionId);
        if (longTermMemoryEnabled && recentMessages.isEmpty()) {
            // 首次读取必须先等旧历史写入完成，否则预算尾部中的真实事件会在新会话里不可见。
            longTermMemoryExtractionCoordinator.completeHistoryBeforeFirstRead(userId, sessionId);
        }
        // 长期记忆一轮只检索一次：压缩分支要用同一批记忆重拼上下文，检索两次会多一次向量化调用，
        // 也会把入选记忆的访问时间写两遍（设计文档 D6 的写回只应在真正取用时发生一次）。
        List<LongTermMemoryRecord> longTermMemories = longTermMemoryEnabled
                ? longTermMemory.retrieveForContext(userId, userMessage)
                : List.of();
        AssembledContext assembled = assembleContext(sessionId, userId, recentMessages, userMessage, null, longTermMemories);
        String context = assembled.text();
        String currentSummary = assembled.summary();
        if (tokenCounter == null) {
            // 单测构造器不注入计量器：跳过压缩判断，行为与改造前一致。
            return new ContextAssembly(context, null, currentSummary, recentMessages);
        }
        int tokens = tokenCounter.count(context);
        int threshold = summaryMemory.getTokenThreshold();
        if (tokens < threshold) {
            return new ContextAssembly(context, null, currentSummary, recentMessages);
        }
        // 压缩失败冷却：失败后原文还在，等上下文再涨一段再重试，避免在阈值附近连续多轮烧生成/复核。
        Integer failedAt = compactionFailureTokens.get(sessionId);
        int retryDelta = summaryMemory.getRetryTokenDelta();
        if (retryDelta > 0 && failedAt != null && tokens < failedAt + retryDelta) {
            log.info("上次压缩失败，上下文尚未增长到重试线，本轮跳过压缩，sessionId={}, tokens={}, 重试线={}",
                    sessionId, tokens, failedAt + retryDelta);
            return new ContextAssembly(context, null, currentSummary, recentMessages);
        }
        int retainedCount = retainedMessageCountForTail(recentMessages);
        int compressibleCount = recentMessages.size() - retainedCount;
        if (compressibleCount <= 0) {
            // 没有可压缩的历史消息：超长只可能来自本轮输入本身，压缩无从下手，交给 768k 硬上限兜底。
            log.warn("上下文超过压缩阈值但没有可压缩的历史消息，sessionId={}, tokens={}, threshold={}",
                    sessionId, tokens, threshold);
            return new ContextAssembly(context, null, currentSummary, recentMessages);
        }
        List<MemoryMessage> compressibleMessages = recentMessages.subList(0, compressibleCount);
        // 先发起事实点抽取再生成摘要：两次调用在同一批消息上独立工作，并行能省掉一次模型调用的等待。
        // 摘要评测关闭长期记忆时连抽取一起跳过：事实点只写长期记忆，不参与摘要正文。
        CompletableFuture<SummaryFactExtractionResult> factsFuture = longTermMemoryEnabled
                ? extractFactsAsync(compressibleMessages)
                : null;
        // 摘要调用尽量复用上一轮真实请求的前缀：25 万 token 只有与上一轮请求逐字节一致时才会命中缓存，
        // 自己另拼一份摘要 prompt 等于每轮冷启动一次（2026-10 缓存命中率优化）。
        SummaryReplayPrefix replay = buildReplayPrefix(sessionId, currentSummary, compressibleMessages);
        String summary = summaryMemory.summarize(sessionId, compressibleMessages, replay);
        if (TextUtil.isBlank(summary)) {
            // 摘要没通过（生成失败或复核不通过）时事实点也不落库：原文仍保留在短期记忆里，
            // 下一轮会重新抽取，现在写入只会让同一批消息重复进长期记忆。
            compactionFailureTokens.put(sessionId, tokens);
            log.warn("摘要生成或复核未通过，本轮不压缩（保留原文），sessionId={}, tokens={}", sessionId, tokens);
            return new ContextAssembly(context, null, currentSummary, recentMessages);
        }
        Long trimCursor = null;
        if (longTermMemoryEnabled) {
            // 设计文档 6.1：摘要与提取都完成后才裁剪。事实点抽取失败时长期记忆游标不能推进，
            // 这时候裁剪会让这批消息彻底离开上下文又没进长期记忆，所以与复核不通过同样处理：宁可继续超阈值。
            SummaryFactExtractionResult factsResult = awaitFacts(factsFuture);
            if (!factsResult.succeeded()) {
                compactionFailureTokens.put(sessionId, tokens);
                log.error("事实点抽取失败，本轮不压缩（保留原文），sessionId={}, tokens={}", sessionId, tokens);
                return new ContextAssembly(context, null, currentSummary, recentMessages);
            }
            saveHighImportanceFacts(userId, sessionId, factsResult.facts());
            // 这批消息已经抽过事实点，长期记忆游标跟着推进；返回的游标值同时是裁剪边界——
            // 只裁游标走过的消息，游标之后（还没进长期记忆）的消息留在短期记忆里（设计文档 A6 / D6）。
            trimCursor = longTermMemoryExtractionCoordinator.advanceAfterCompaction(userId, compressibleMessages);
        }
        // 摘要评测没有长期记忆可依赖，被压缩的消息只剩摘要一个载体，尾部同样按 token 预算保留；
        // 生产路径还要按游标保住尚未提取的消息（A6 / D6）。
        shortTermMemory.keepRecentMessages(sessionId, retainedCount, trimCursor);
        // 重拼时必须显式带上刚生成的摘要：检查点要等压缩后的计量结果才能写库，
        // 此刻 getSummary 读库拿到的还是上一版摘要，不传新摘要模型就看不到刚被压缩掉的历史（2026-09-19 联调实测）。
        List<MemoryMessage> remainingMessages = shortTermMemory.getRecentMessages(sessionId);
        AssembledContext compressedContext = assembleContext(sessionId, userId,
                remainingMessages, userMessage, summary, longTermMemories);
        String compressed = compressedContext.text();
        int compressedTokens = tokenCounter.count(compressed);
        // 摘要同时写进会话历史（role=summary 的检查点）：刷新页面或重启后端后，仍然能看到"这里压缩过"和摘要正文。
        summaryMemory.recordCompactionCheckpoint(sessionId, userId, summary, compressibleCount, tokens, compressedTokens);
        // 压缩成功：清掉失败冷却记录，下次到阈值正常触发。
        compactionFailureTokens.remove(sessionId);
        log.info("上下文已按 token 压缩，sessionId={}, 压缩前={} token, 压缩后={} token, 摘要前缀复用={}",
                sessionId, tokens, compressedTokens, replay != null);
        return new ContextAssembly(compressed,
                new CompactionNotice(summary, compressibleCount, tokens, compressedTokens),
                compressedContext.summary(), remainingMessages);
    }

    /**
     * 构造摘要调用的可复用信封。
     *
     * <p>直接使用上一轮真实请求的完整信封（system/tools/thinking/消息列表），压缩时只在它末尾追加一条指令。
     * 不按「被压缩消息」重新拼前缀：真实请求的消息 = 历史块 + 本轮用户输入，两者的分隔和空行无法从被压缩消息
     * 反推出来，拼出来的文本一定在某一处对不上，缓存就断了。
     *
     * <p>摘要版本和历史覆盖范围都必须吻合。规划轮次不会更新信封，普通轮次的工具结果也可能产生在
     * 首次请求之后；这些新增消息一旦进入压缩范围，必须改用完整原文，不能只拿旧前缀生成和复核后裁剪。
     */
    private SummaryReplayPrefix buildReplayPrefix(String sessionId, String summary, List<MemoryMessage> messages) {
        LastRequest last = lastRequestPrefixes.get(sessionId);
        if (last == null || last.envelope() == null) {
            return null;
        }
        if (!TextUtil.trimToDefault(last.summary(), "").equals(TextUtil.trimToDefault(summary, ""))) {
            return null;
        }
        if (last.history().size() < messages.size()
                || !last.history().subList(0, messages.size()).equals(messages)) {
            return null;
        }
        return new SummaryReplayPrefix(last.envelope());
    }

    /**
     * 把本轮采集到的真实请求信封存给下一轮压缩复用，并关闭采集作用域。
     * <p>
     * 没有新信封时保留上一份记录；下一次只有它确实覆盖全部待压缩历史时才允许复用。
     */
    private void storeReplayEnvelope(String sessionId, ChatReplayContext.Scope scope, List<MemoryMessage> history) {
        try {
            if (scope == null || scope.envelope() == null || TextUtil.isBlank(sessionId)) {
                return;
            }
            if (lastRequestPrefixes.size() >= MAX_CACHED_REPLAY_SESSIONS
                    && !lastRequestPrefixes.containsKey(sessionId)) {
                lastRequestPrefixes.keySet().stream().findFirst().ifPresent(lastRequestPrefixes::remove);
            }
            lastRequestPrefixes.put(sessionId, new LastRequest(scope.envelope(),
                    TextUtil.trimToDefault(scope.usedSummary(), ""), history));
        } finally {
            if (scope != null) {
                scope.close();
            }
        }
    }

    /**
     * 按 token 预算计算压缩后要保留尾部的消息条数。
     *
     * <h3>为什么按 token 而不是条数</h3>
     * 工具执行结果进入会话历史后，一条 Tool 消息可能是一轮里最长的消息；固定留 2 条会只留下
     * 「最后一个工具结果 + 最终回答」，把用户问题和本轮前面的工具证据裁光。按尾部 token 预算保留，
     * 保留量与模型窗口、每轮成本同口径，默认 16k 约等于几条到十几条消息，可配置。
     *
     * <h3>边界规则</h3>
     * 只从尾部往前累加整条消息：要么整条留下，要么整条交给摘要，不切断任何一条消息。
     * 工具调用和结果按原生协议是两条相邻消息，如果裁剪边界正好落在 Tool 结果上，
     * 要把前一条 assistant 工具调用一起留下，否则模型会看到一条没有调用的结果。
     * 最后一条消息即使自己就超过预算也至少留 1 条，否则压缩后可能一条历史都不剩。
     *
     * <h3>兜底</h3>
     * 拿不到 token 计量器（单测最小构造）或预算配置为 0 时，退回 {@code retained-message-count} 的旧口径。
     */
    private int retainedMessageCountForTail(List<MemoryMessage> messages) {
        int fallbackCount = summaryMemory.getRetainedMessageCount();
        int tokenBudget = summaryMemory.getRetainedTokenBudget();
        if (tokenCounter == null || tokenBudget <= 0 || messages.isEmpty()) {
            return fallbackCount;
        }
        int usedTokens = 0;
        int retainedCount = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            // 工具调用消息正文为空，按渲染后的 ToolCall 文本计量，和实际进上下文的体积一致。
            int messageTokens = tokenCounter.count(messages.get(index).promptText());
            // 已留了至少一条时，再往前一条会超预算就停：宁可少留，也不把这条切成两半。
            if (retainedCount > 0 && usedTokens + messageTokens > tokenBudget) {
                break;
            }
            usedTokens += messageTokens;
            retainedCount++;
            if (usedTokens >= tokenBudget) {
                break;
            }
        }
        // 原生协议配对边界：裁剪起点如果是 tool 结果，必须把前一条 assistant 调用一起留下。
        int startIndex = messages.size() - retainedCount;
        if (startIndex > 0 && startIndex < messages.size()
                && messages.get(startIndex).isToolResult()
                && messages.get(startIndex - 1).isToolCall()) {
            retainedCount++;
        }
        return retainedCount;
    }

    /** 评测只取真实记忆上下文，避免为了回放历史而重新生成历史中的助手答案。 */
    public PreparedMemoryContext prepareMemoryContext(String sessionId, String userId, String question) {
        ContextAssembly assembly = buildConversationContext(sessionId, userId, question);
        CompactionNotice notice = assembly.compaction();
        return new PreparedMemoryContext(assembly.text(), notice == null ? null : Map.of(
                "summary", notice.summary(), "compressed_message_count", notice.compressedMessageCount(),
                "tokens_before", notice.tokensBefore(), "tokens_after", notice.tokensAfter()));
    }

    /**
     * 评测摘要策略的上下文：只保留「摘要 + 最近消息」，不检索也不写长期记忆。
     * <p>
     * 单独开这个入口而不是复用 prepareMemoryContext：摘要评测要确认压缩本身丢了多少信息，
     * 如果同时开长期记忆，压缩时抽到的高分事实点会在回答时被召回，把摘要的缺口补上，测出的结果偏乐观。
     * 生产默认链路不调用这个方法，行为不变。
     */
    public PreparedMemoryContext prepareSummaryContext(String sessionId, String userId, String question) {
        ContextAssembly assembly = buildConversationContext(sessionId, userId, question, false);
        CompactionNotice notice = assembly.compaction();
        return new PreparedMemoryContext(assembly.text(), notice == null ? null : Map.of(
                "summary", notice.summary(), "compressed_message_count", notice.compressedMessageCount(),
                "tokens_before", notice.tokensBefore(), "tokens_after", notice.tokensAfter()));
    }

    public record PreparedMemoryContext(String text, Map<String, Object> compression) {
    }

    /**
     * 并行发起事实点抽取（设计文档 B2）。
     * <p>
     * 抽取走独立线程：LLM 调用是阻塞的，放在主线程里会让压缩那一轮白等一次模型调用。
     * LlmUsageContext 是 ThreadLocal，必须先在线程内取出来带过去，否则这次调用的用量会丢场景标签。
     * 抽取器自身已把异常收敛成空列表，这里再兜一层，保证压缩主流程不会因为抽取失败而中断。
     */
    private CompletableFuture<SummaryFactExtractionResult> extractFactsAsync(List<MemoryMessage> messages) {
        if (summaryFactExtractor == null) {
            // 单测构造器不注入抽取器：压缩行为与改造前一致，不写长期记忆。
            return CompletableFuture.completedFuture(SummaryFactExtractionResult.ok(List.of()));
        }
        LlmUsageContext usageContext = LlmUsageContext.current();
        MemoryObservationContext.Snapshot observation = MemoryObservationContext.current();
        if (observation != null) {
            observation.pendingCalls().begin();
        }
        return CompletableFuture.supplyAsync(() -> {
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.restore(usageContext);
                 MemoryObservationContext.Scope observationScope = MemoryObservationContext.restore(observation)) {
                return summaryFactExtractor.extract(messages);
            } catch (Exception exception) {
                log.warn("摘要事实点抽取失败，本轮不写长期记忆，error={}", exception.getMessage());
                return SummaryFactExtractionResult.failed();
            } finally {
                if (observation != null) {
                    observation.pendingCalls().end();
                }
            }
        });
    }

    /**
     * 等待并行抽取结果。
     * <p>
     * 异常已在分支内兜底，这里只是防止 future 层面的意外中断压缩主流程；
     * 返回失败标记而不是空列表，调用方据此决定「本轮不裁剪、游标不推进」。
     */
    private SummaryFactExtractionResult awaitFacts(CompletableFuture<SummaryFactExtractionResult> factsFuture) {
        try {
            return factsFuture.join();
        } catch (Exception exception) {
            log.warn("等待事实点抽取结果失败，本轮不写长期记忆，error={}", exception.getMessage());
            return SummaryFactExtractionResult.failed();
        }
    }

    /**
     * 把达到高分线的事实点写入长期记忆（设计文档 B3 / D5）。
     * <p>
     * 低于高分线的事实点只留在摘要里，不单独占记忆条目——记忆列表的空间要留给「丢了会明显影响后续协作」的内容。
     * 单条写入失败只打 warn：一条记忆没存上，不应该影响已经压缩完成的上下文和用户这一轮的回答。
     * <p>
     * 同一批里归到同一个 memory_key 的事实点现在各自成条（步骤 6 起记忆以 id 为身份），
     * 不再像以前那样后一条把前一条覆盖掉；是否合并由步骤 7 的写入决策负责。
     */
    private void saveHighImportanceFacts(String userId, String sessionId, List<SummaryFactPoint> facts) {
        if (facts.isEmpty()) {
            // 没有日志就无法区分「抽取跑了但没抽到」和「抽取根本没跑」，实测中这两件事排查代价完全不同。
            log.info("摘要事实点已处理，userId={}, 抽取 0 条，未写入长期记忆", userId);
            return;
        }
        int threshold = summaryMemory.getFactImportanceThreshold();
        List<SummaryFactPoint> highImportanceFacts = new ArrayList<>();
        for (SummaryFactPoint fact : facts) {
            // importance 为空说明抽取器没有归一化成功，按 0 处理落到高分线以下，宁可不写也不写脏数据。
            int importance = fact.importance() == null ? 0 : fact.importance();
            if (importance >= threshold) {
                highImportanceFacts.add(fact);
            }
        }
        if (highImportanceFacts.isEmpty()) {
            log.info("摘要事实点已处理，userId={}, 抽取 {} 条, 达到高分线({})并写入长期记忆 0 条",
                    userId, facts.size(), threshold);
            return;
        }
        // 入队后由消费者跑阶段二写入决策：高分事实点也要过去重与冲突判断，不能绕过统一写入口（设计文档 6.1）。
        if (longTermMemoryExtractionCoordinator.enqueueFacts(userId, sessionId, highImportanceFacts)) {
            log.info("摘要事实点已入队，userId={}, 抽取 {} 条, 达到高分线({})共 {} 条",
                    userId, facts.size(), threshold, highImportanceFacts.size());
            return;
        }
        // 队列不可用时直接落库兜底：高分事实点是「压缩后就只剩这一份」的内容，不能因为 Redis 抖动丢掉。
        int savedCount = 0;
        for (SummaryFactPoint fact : highImportanceFacts) {
            int importance = fact.importance() == null ? 0 : fact.importance();
            try {
                longTermMemory.save(userId, fact.memoryKey(), fact.storageContent(), sessionId, importance);
                savedCount++;
            } catch (Exception exception) {
                log.warn("高分事实点写入长期记忆失败，userId={}, memoryKey={}, error={}",
                        userId, fact.memoryKey(), exception.getMessage());
            }
        }
        log.info("摘要事实点已处理（队列不可用，直接落库），userId={}, 抽取 {} 条, 达到高分线({})并写入长期记忆 {} 条",
                userId, facts.size(), threshold, savedCount);
    }

    /**
     * 按固定顺序拼接「长期记忆 → 摘要 → 短期记忆 → 用户输入」。
     * <p>
     * 顺序天然满足「固定文本靠前、变化内容靠后」的 KV cache 要求；
     * 这次重构只改各段的内容来源，不改顺序。
     *
     * <p>长期记忆由调用方检索好再传进来，而不是在这里查：压缩那一轮需要重拼一次同样的上下文，
     * 如果两处各查一次，一次对话就会做两次向量化、并重复写回访问时间。
     *
     * @param summaryOverride 本次要用的摘要；为 null 表示按常规路径从持久化摘要读取。
     *                        压缩那一轮的新摘要还没写进检查点——检查点的 token_count 需要压缩后的计量结果，
     *                        写完才能入库；此时 {@code summaryMemory.getSummary} 走数据库只会拿到上一版摘要
     *                        （第一次压缩时为空），不显式传入就会出现「原文已裁、摘要没进上下文」的空档
     *                        （设计文档 D6 要避免的正是这种空档）。
     * @param longTermMemories 本次要注入的长期记忆（已按三维打分取 top-K）
     */
    private AssembledContext assembleContext(String sessionId, String userId, List<MemoryMessage> messages,
                                             String userMessage, String summaryOverride,
                                             List<LongTermMemoryRecord> longTermMemories) {
        String summary = summaryOverride == null ? summaryMemory.getSummary(sessionId) : summaryOverride;
        StringBuilder conversation = new StringBuilder();
        // 顺序按「跨轮稳定 → 每轮变化」排：摘要 → 短期记忆 → 长期记忆 → 用户输入。
        // 长期记忆按当前问题检索，每轮内容都可能变；它放在摘要/短期记忆后面，前面那段才是稳定前缀，
        // DeepSeek 的前缀缓存才有机会命中（2026-10 缓存命中率优化）。
        appendSummary(conversation, summary);
        appendMemory(conversation, messages);
        appendLongTermMemory(conversation, longTermMemories);
        conversation.append("Human:").append(userMessage).append("\n\n");
        return new AssembledContext(conversation.toString(), summary);
    }

    /**
     * 每次调用模型前计量当前请求体积（设计文档 A3）。
     * <p>
     * 这里只观测不压缩：请求内部的增长只可能来自工具结果和本轮用户输入——
     * 工具结果进上下文前已被 {@link ToolResultCompressor} 限制体积，用户输入本身没有可压缩的对象，
     * 记忆部分在组装时已经压缩过。真正的硬兜底是 LLMService 的 768k token 上限。
     */
    private void logContextBudget(String systemPrompt, StringBuilder conversation) {
        if (tokenCounter == null) {
            return;
        }
        // 与压缩触发保持同一口径：只算组装好的对话文本，不含系统提示词与工具定义。
        // 2026-09-19 作者实测踩到过：原来把 systemPrompt 也加进来，于是日志一直报「已超过记忆压缩阈值」，
        // 但压缩根本没触发（判断口径里没有系统提示词），看起来像压缩失灵。
        int contextTokens = tokenCounter.count(conversation.toString());
        int threshold = summaryMemory.getTokenThreshold();
        if (contextTokens >= threshold) {
            log.warn("对话上下文已超过记忆压缩阈值，tokens={}, threshold={}（含系统提示词的整请求约 {} token）",
                    contextTokens, threshold, contextTokens + tokenCounter.count(systemPrompt));
        }
    }

    /**
     * 工具结果进上下文前的体积控制（设计文档 A7 / D18）。
     * <p>
     * 只影响模型看到的那一份内容；原文仍完整写入 t_agent_step.tool_output 供审计与回查。
     */
    private String boundedToolResult(String toolName, String result) {
        if (toolResultCompressor == null) {
            return result;
        }
        return toolResultCompressor.compressIfNeeded(toolName, result);
    }

    /**
     * 一次上下文组装的结果：文本、压缩通知，以及本次使用的摘要和历史快照。
     * <p>
     * 带 summary 是为了让调用方在真正发请求前记住「这一轮用的哪版摘要」，
     * 下一轮压缩时同时检查摘要版本与历史覆盖范围，避免重放遗漏首次请求之后才产生的消息。
     */
    private record ContextAssembly(String text, CompactionNotice compaction, String summary, List<MemoryMessage> history) {
        private ContextAssembly {
            // 请求发出后会继续追加本轮结果，覆盖范围必须固定在组装上下文这一刻。
            history = List.copyOf(history);
        }
    }

    /** 一次 assembleContext 的产物：拼好的上下文文本 + 本次使用的摘要。 */
    private record AssembledContext(String text, String summary) {
    }

    /**
     * 一次压缩的事实：摘要正文、被压缩的消息条数、压缩前后的 token 数。
     * 前端用它显示「已把 N 条历史压缩为摘要（X → Y token）」，用户不用对着几秒停顿猜发生了什么。
     */
    private record CompactionNotice(String summary, int compressedMessageCount, int tokensBefore, int tokensAfter) {
    }

    /** 把压缩结果作为 context_compressed 事件推给前端；没有发生压缩时什么都不发。 */
    private void sendCompactionEvent(SseEmitter emitter, CompactionNotice notice) throws IOException {
        if (notice == null) {
            return;
        }
        sendSseEvent(emitter, "context_compressed", Map.of(
                "compressedMessageCount", notice.compressedMessageCount(),
                "tokensBefore", notice.tokensBefore(),
                "tokensAfter", notice.tokensAfter(),
                "summary", notice.summary()));
    }

    /**
     * 持久化一次「面向用户的聊天回合」：写消息表、写短期记忆，再按预算决定是否抽取长期记忆。
     * <p>
     * 抽成方法是因为文本路、结构化路与规划模式在拿到 finalAnswer 后都要做同一件事，避免多处重复。
     * 摘要压缩已上移到上下文组装（按 token 判断），这里不再触发。
     *
     * @param toolSteps 本轮执行过的工具步骤；每个步骤按原生协议写成两条消息（assistant 工具调用 + tool 结果），
     *                  不写模型思考/推理。非工具步骤（action 为空）直接忽略。
     */
    private void persistChatTurn(String sessionId, String userId, String userMessage, String finalAnswer,
                                 int assistantTokenCount, List<AgentStep> toolSteps) {
        persistChatTurn(sessionId, userId, userMessage, finalAnswer, assistantTokenCount, toolSteps, null, null);
    }

    /**
     * 持久化一次聊天回合，并处理「客户端已断开」的情况。
     * <p>
     * 写入顺序固定为 user → (assistant tool_call → tool 结果)... → assistant：
     * 工具调用与结果两条消息相邻、共用 toolCallId，消息 id 仍按发生顺序递增，
     * 长期记忆提取的区间终点（assistant id）也仍然是本轮最后一条。
     *
     * @param toolSteps     本轮工具执行步骤，按原生协议写成 assistant 调用 + tool 结果两条消息
     * @param cancelled     客户端断开标记；置位时这一轮不算完成，只保留用户消息（设计文档 C9 / D10）
     * @param turnRecorded  回填「这一轮已经处理过」的标记，断开兜底逻辑据此避免重复写入
     */
    private void persistChatTurn(String sessionId, String userId, String userMessage, String finalAnswer,
                                 int assistantTokenCount, List<AgentStep> toolSteps,
                                 AtomicBoolean cancelled, AtomicBoolean turnRecorded) {
        if (cancelled != null && cancelled.get()) {
            persistCancelledTurn(sessionId, userMessage);
            if (turnRecorded != null) {
                turnRecorded.set(true);
            }
            return;
        }
        ConversationMessageRecord userRecord = buildConversationMessage(userId, sessionId, "user", userMessage, null, 0);
        List<PersistedToolStep> toolStepRecords = buildToolStepRecords(userId, sessionId, toolSteps);
        ConversationMessageRecord assistantRecord = buildConversationMessage(userId, sessionId, "assistant", finalAnswer, null, assistantTokenCount);
        persistConversationMessages(userRecord, toolStepRecords, assistantRecord);
        shortTermMemory.append(sessionId, "Human", userMessage, userRecord.getId());
        for (PersistedToolStep toolStep : toolStepRecords) {
            // 原生协议：先追加 assistant 的工具调用消息（正文空，渲染成 ToolCall: 工具(参数)），
            // 再追加对应的 Tool 结果消息；两条用 toolCallId 配对，裁剪时必须成对保留。
            shortTermMemory.append(sessionId, MemoryMessage.toolCall("AI", toolStep.callRecord().getId(),
                    toolStep.toolCallId(), toolStep.toolName(), toolStep.toolArguments()));
            shortTermMemory.append(sessionId, MemoryMessage.toolResult(ShortTermMemory.ROLE_TOOL,
                    toolStep.resultRecord().getId(), toolStep.toolCallId(), toolStep.toolName(),
                    toolStep.resultRecord().getContent()));
        }
        shortTermMemory.append(sessionId, "AI", finalAnswer, assistantRecord.getId());
        // 摘要触发已上移到上下文组装（按 token 判断）；长期记忆提取由协调器决定是否入队，
        // 带的区间终点是本轮最后一条消息（助手最终回答）的 id（设计文档 C7）。
        longTermMemoryExtractionCoordinator.afterTurn(userId, sessionId, userMessage, finalAnswer, assistantRecord.getId());
        if (turnRecorded != null) {
            turnRecorded.set(true);
        }
    }

    /**
     * 把本轮工具步骤组装成原生协议的两条消息。
     * <p>
     * assistant 工具调用消息：content 为空（模型思考/推理不写进会话历史），工具名、参数摘要、配对 id 落到
     * tool_name/tool_arguments/tool_call_id 三列；tool 结果消息：content 是结果（≤2000 字），
     * 与调用共用 tool_call_id。持久化时不再调用 ToolResultCompressor，否则每个工具步骤都要多等一次模型调用。
     */
    private List<PersistedToolStep> buildToolStepRecords(String userId, String sessionId,
                                                         List<AgentStep> toolSteps) {
        if (toolSteps == null || toolSteps.isEmpty()) {
            return List.of();
        }
        List<PersistedToolStep> records = new ArrayList<>();
        for (AgentStep step : toolSteps) {
            if (step == null || TextUtil.isBlank(step.action())) {
                continue;
            }
            // tool_name 列只有 64 字符：超长工具名按列宽截断，参数摘要和结果按上面的上限截断。
            String toolName = TextUtil.abbreviate(step.action(), 64);
            String arguments = TextUtil.abbreviate(
                    TextUtil.trimToDefault(step.actionInput(), ""), TOOL_MESSAGE_ARGUMENT_MAX_CHARS);
            String result = TextUtil.abbreviate(
                    TextUtil.trimToDefault(step.observation(), ""), TOOL_MESSAGE_RESULT_MAX_CHARS);
            String toolCallId = newToolCallId();
            ConversationMessageRecord callRecord = buildConversationMessage(userId, sessionId, "assistant", "",
                    toolName, toolCallId, arguments, 0);
            ConversationMessageRecord resultRecord = buildConversationMessage(userId, sessionId, "tool", result,
                    toolName, toolCallId, null, 0);
            records.add(new PersistedToolStep(toolCallId, toolName, arguments, callRecord, resultRecord));
        }
        return records;
    }

    /** 生成原生协议的工具调用 id：assistant 调用与 tool 结果共用，普通消息不用。 */
    private String newToolCallId() {
        return "call_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 一次工具步骤落库后的两条消息：assistant 调用 + tool 结果，两条共用 toolCallId。 */
    private record PersistedToolStep(String toolCallId, String toolName, String toolArguments,
                                     ConversationMessageRecord callRecord,
                                     ConversationMessageRecord resultRecord) {
    }

    /**
     * 客户端断开 / 用户点「停止生成」后的收尾（设计文档 C9 / D10）。
     * <p>
     * 只把用户消息写进短期记忆，等下次调用时一起发给模型。刻意不写消息表：
     * 消息表里没有这条记录，长期记忆提取游标就不会把它当成待抽取的消息，也就不会入库。
     * 代价是这条消息在下一次压缩时会被摘要吞掉（D10 已知并接受的取舍）。
     */
    private void persistCancelledTurn(String sessionId, String userMessage) {
        if (TextUtil.isBlank(userMessage)) {
            return;
        }
        shortTermMemory.append(sessionId, "Human", userMessage);
        log.info("客户端已断开，本轮只保留用户消息到短期记忆（不写消息表、不写摘要、不提取长期记忆），sessionId={}", sessionId);
    }

    /**
     * 创建 Agent 持久化上下文。
     * <p>
     * 这里同时 upsert 会话和插入 trace，是为了把“用户发起一次 Agent 请求”作为可回放的顶层事实记录下来。
     * 所有异常只记录 warn，确保数据库短暂不可用时不影响主回答链路。
     */
    private AgentPersistenceContext startAgentTrace(String sessionId, String userId, String userMessage) {
        String traceId = UUID.randomUUID().toString();
        AgentPersistenceContext context = new AgentPersistenceContext(traceId, sessionId);
        LocalDateTime now = LocalDateTime.now();
        try {
            if (conversationSessionMapper != null) {
                ConversationSessionRecord sessionRecord = new ConversationSessionRecord();
                sessionRecord.setSessionId(sessionId);
                sessionRecord.setUserId(userId);
                sessionRecord.setTitle(buildSessionTitle(userMessage));
                sessionRecord.setStatus(0);
                sessionRecord.setCreateTime(now);
                sessionRecord.setUpdateTime(now);
                conversationSessionMapper.upsertSession(sessionRecord);
            }
            if (agentTraceMapper != null) {
                AgentTraceRecord traceRecord = new AgentTraceRecord();
                traceRecord.setTraceId(traceId);
                traceRecord.setSessionId(sessionId);
                traceRecord.setUserInput(TextUtil.trimToDefault(userMessage, ""));
                traceRecord.setStatus(0);
                traceRecord.setTotalTokens(0);
                traceRecord.setTotalSteps(0);
                traceRecord.setStartTime(now);
                traceRecord.setCreateTime(now);
                agentTraceMapper.insertTrace(traceRecord);
                context.enabled = true;
            }
        } catch (Exception exception) {
            log.warn("Agent 执行链路初始化持久化失败，sessionId={}, traceId={}, error={}",
                    sessionId, traceId, exception.getMessage());
        }
        return context;
    }

    /**
     * 记录 Agent 单步执行快照。
     * <p>
     * 单步写入失败不会影响 ReAct 主循环，因为步骤表属于可观测旁路；主答案的正确性不依赖该表。
     */
    private void persistAgentStep(AgentPersistenceContext context, int stepIndex, String stepType, String content,
                                  String toolName, String toolInput, String toolOutput, int tokenCount) {
        // 工具步骤先收进内存列表，再决定要不要写 trace 库：即使可观测旁路不可用（enabled=false），
        // 本轮工具执行记录仍要写进会话历史——模型这一轮实际看到过这些结果，丢了下一轮就再也看不到。
        if (context != null && TextUtil.hasText(toolName)) {
            context.toolSteps.add(new AgentStep(stepIndex, content, toolName, toolInput, toolOutput));
        }
        if (context == null || !context.enabled || agentTraceMapper == null) {
            return;
        }
        try {
            AgentStepRecord stepRecord = new AgentStepRecord();
            stepRecord.setTraceId(context.traceId);
            stepRecord.setStepIndex(stepIndex);
            stepRecord.setStepType(stepType);
            stepRecord.setContent(TextUtil.trimToDefault(content, ""));
            stepRecord.setToolName(TextUtil.trimToNull(toolName));
            stepRecord.setToolInput(TextUtil.trimToNull(toolInput));
            stepRecord.setToolOutput(TextUtil.trimToNull(toolOutput));
            stepRecord.setTokenCount(tokenCount);
            stepRecord.setCreateTime(LocalDateTime.now());
            agentTraceMapper.insertStep(stepRecord);
            context.totalSteps = Math.max(context.totalSteps, stepIndex);
        } catch (Exception exception) {
            log.warn("Agent 执行步骤持久化失败，traceId={}, stepIndex={}, error={}",
                    context.traceId, stepIndex, exception.getMessage());
        }
    }

    /**
     * 回填 Agent trace 终态。
     * <p>
     * trace 以 status 表达结果，成功和失败都落在同一张表，后续排障可以按 sessionId 找完整执行历史。
     */
    private void completeAgentTrace(AgentPersistenceContext context, int status, String finalOutput,
                                    int totalSteps, String errorMsg) {
        if (context == null || !context.enabled || agentTraceMapper == null) {
            return;
        }
        try {
            AgentTraceRecord traceRecord = new AgentTraceRecord();
            traceRecord.setTraceId(context.traceId);
            traceRecord.setFinalOutput(finalOutput);
            traceRecord.setStatus(status);
            traceRecord.setTotalTokens(context.totalTokens);
            traceRecord.setTotalSteps(Math.max(totalSteps, context.totalSteps));
            traceRecord.setEndTime(LocalDateTime.now());
            traceRecord.setErrorMsg(TextUtil.abbreviate(TextUtil.trimToNull(errorMsg), 512));
            agentTraceMapper.completeTrace(traceRecord);
        } catch (Exception exception) {
            log.warn("Agent 执行链路终态持久化失败，traceId={}, error={}", context.traceId, exception.getMessage());
        }
    }

    /**
     * 将完整用户问答（含本轮工具调用与结果）写入 MySQL 消息表，并把生成的主键回填到传入的记录对象上。
     * <p>
     * 主键必须回填：调用方要用它给短期记忆带上消息 id（长期记忆游标的比较基准，设计文档 A6 / C4）。
     * 用户消息不记录 token，是因为当前 token 是模型调用级别数据，无法可靠拆分到用户消息本身；
     * 工具调用/结果也不记 token，它们的体积已经由参数摘要/结果截断上限控制。
     * 写入失败只打 warn，记录对象上的 id 保持 null。
     */
    private void persistConversationMessages(ConversationMessageRecord userRecord,
                                             List<PersistedToolStep> toolSteps,
                                             ConversationMessageRecord assistantRecord) {
        if (conversationSessionMapper == null) {
            return;
        }
        try {
            conversationSessionMapper.insertMessage(userRecord);
            for (PersistedToolStep toolStep : toolSteps) {
                // 原生协议：assistant 工具调用消息在前，tool 结果消息在后，两条共用 toolCallId。
                conversationSessionMapper.insertMessage(toolStep.callRecord());
                conversationSessionMapper.insertMessage(toolStep.resultRecord());
            }
            conversationSessionMapper.insertMessage(assistantRecord);
        } catch (Exception exception) {
            log.warn("会话消息持久化失败，sessionId={}, error={}", userRecord.getSessionId(), exception.getMessage());
        }
    }

    /**
     * 组装一条待落库的消息（普通消息或工具结果）。
     * <p>
     * 带上 userId 是为了让消息表自己就能按用户捞出增量（设计文档 C5）：长期记忆的游标只认消息 id，
     * 若还要靠 JOIN 会话表判断归属，会话被归档或删除时会连带影响增量查询。
     */
    private ConversationMessageRecord buildConversationMessage(String userId, String sessionId, String role, String content,
                                                               String toolName, int tokenCount) {
        return buildConversationMessage(userId, sessionId, role, content, toolName, null, null, tokenCount);
    }

    /**
     * 组装一条待落库的消息（含原生协议工具字段）。
     *
     * @param toolCallId    工具调用配对 id；普通消息为 null
     * @param toolArguments 工具调用参数摘要；只有 assistant 调用消息有值，tool 结果为 null
     */
    private ConversationMessageRecord buildConversationMessage(String userId, String sessionId, String role, String content,
                                                               String toolName, String toolCallId, String toolArguments,
                                                               int tokenCount) {
        ConversationMessageRecord record = new ConversationMessageRecord();
        record.setSessionId(sessionId);
        record.setUserId(userId);
        record.setRole(role);
        record.setContent(TextUtil.trimToDefault(content, ""));
        record.setToolName(TextUtil.trimToNull(toolName));
        record.setToolCallId(TextUtil.trimToNull(toolCallId));
        record.setToolArguments(TextUtil.trimToNull(toolArguments));
        record.setTokenCount(tokenCount);
        record.setCreateTime(LocalDateTime.now());
        return record;
    }

    private String buildSessionTitle(String userMessage) {
        return TextUtil.preview(userMessage, 60, "新会话");
    }

    /**
     * 计算本步 token。
     * <p>
     * 优先使用 totalTokens；供应商只返回 prompt/completion 时再相加，所有字段缺失时返回 0，避免空值写入整型列。
     */
    private int calculateTokenCount(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        if (totalTokens != null && totalTokens > 0) {
            return totalTokens;
        }
        int prompt = promptTokens == null ? 0 : Math.max(0, promptTokens);
        int completion = completionTokens == null ? 0 : Math.max(0, completionTokens);
        return prompt + completion;
    }

    /**
     * Agent 持久化上下文。
     * <p>
     * 用小对象承载 traceId 和累计值，避免主循环中散落多个可变局部变量，让成功、失败和超限分支都能统一回填。
     */
    private static class AgentPersistenceContext {
        private final String traceId;
        private final String sessionId;
        /**
         * 本轮已执行的工具步骤（按执行顺序）。
         * 由 {@link #persistAgentStep} 在每次工具执行后收集，供 persistChatTurn 按原生协议写成
         * assistant 工具调用 + tool 结果两条消息。
         */
        private final List<AgentStep> toolSteps = new ArrayList<>();
        private boolean enabled;
        private int totalTokens;
        private int totalSteps;

        private AgentPersistenceContext(String traceId, String sessionId) {
            this.traceId = traceId;
            this.sessionId = sessionId;
        }
    }

    /**
     * 结构化 ReAct 循环（阶段 5.4）：每步用 {@link LLMService#chatStructured} 产出受 schema 约束的 {@link ReActStep}，
     * 取代「自由文本 + 正则解析」。工具执行 / 迭代上限 / 对话回灌与文本路一致——只换了「每步怎么从 LLM 拿决策」。
     * <p>
     * run 与 runTask 共用这一份结构化循环（记忆读写差异由各自承担）：文本路 + 结构化路 × run + runTask 若各写一份
     * 会变成四份循环，到此 rule of three 已越过，抽取共享循环才划算（5.3b-1 时仅两个调用点故暂未抽）。
     *
     * @param systemPrompt 结构化内核的系统提示词（含 schema 约束 + 工具描述）
     * @param conversation 对话上下文字符串（可原地修改），用于历史回灌
     * @param steps 步骤追踪，原地累加供调用方组装响应
     * @return 最终答案；超出迭代上限返回 null（由调用方转成「迭代次数超过上限」错误）
     */
    private String runStructuredLoop(String systemPrompt, StringBuilder conversation, List<AgentStep> steps) {
        return runStructuredLoop(systemPrompt, conversation, steps, null);
    }

    private String runStructuredLoop(String systemPrompt, StringBuilder conversation, List<AgentStep> steps,
                                     AgentPersistenceContext persistenceContext) {
        return runStructuredLoop(systemPrompt, conversation, steps, persistenceContext, 0);
    }

    /**
     * 原生 Function Calling 失败后可从已完成的轮次继续，避免重新编号并重复执行已经完成的工具调用。
     */
    private String runStructuredLoop(String systemPrompt, StringBuilder conversation, List<AgentStep> steps,
                                     AgentPersistenceContext persistenceContext, int initialIteration) {
        int iteration = initialIteration;
        while (true) {
            iteration++;
            // 迭代上限兜底：与文本路一致
            if (iteration > MAX_ITERATIONS) {
                return null;
            }
            log.info("结构化 ReAct 第{}轮迭代...", iteration);

            // chatStructured 内部已对解析失败重试；仍失败则抛出（含成本 guard 的 400），交上层按错误返回，不在此吞掉
            logContextBudget(systemPrompt, conversation);
            StructuredCallResult<ReActStep> callResult =
                    llmService.chatStructuredWithUsage(systemPrompt, conversation.toString(), ReActStep.class);
            ReActStep step = callResult.entity();
            int stepTokenCount = calculateTokenCount(
                    callResult.promptTokens(),
                    callResult.completionTokens(),
                    callResult.totalTokens()
            );
            if (persistenceContext != null) {
                persistenceContext.totalTokens += stepTokenCount;
            }

            // 优先终止：拿到非空 finalAnswer 即结束
            if (step.isFinal()) {
                persistAgentStep(persistenceContext, iteration, "final", step.finalAnswer(), null, null, null, stepTokenCount);
                return step.finalAnswer();
            }

            // 合法 JSON 但既无 finalAnswer 也无 action：回喂错误让模型补齐（与文本路格式错误同构）
            if (!step.hasAction()) {
                steps.add(new AgentStep(iteration, step.thought(), null, null, null));
                persistAgentStep(persistenceContext, iteration, "thought", step.thought(), null, null, null, stepTokenCount);
                conversation.append("ERROR：既未给出 finalAnswer 也未给出 action，请重试！\n\n");
                continue;
            }

            // 执行工具：actionInput 缺省按空串处理，语义同文本路
            // 选择空串而非 null 是因为 ToolExecutor 内部对 null 参数的防御处理可能不一致，
            // 空串传递"我知道这个工具存在，但参数为空"的明确语义
            ToolCall action = new ToolCall(step.action(), step.actionInput() == null ? "" : step.actionInput());
            Observation observation = toolExecutor.execute(action);
            // 工具结果进上下文前先做体积控制（A7 / D18）：原文仍完整落 t_agent_step
            String boundedResult = boundedToolResult(observation.toolName(), observation.result());
            steps.add(new AgentStep(iteration, step.thought(), action.name(), action.arguments(), observation.result()));
            persistAgentStep(persistenceContext, iteration, "action", step.thought(),
                    action.name(), action.arguments(), observation.result(), stepTokenCount);

            // 对话历史用文本回灌即可（模型读历史无需结构化、省 token）；下一步仍按 schema 产出
            conversation.append("AI:\n").append("Thought:").append(TextUtil.trimToDefault(step.thought(), "")).append("\n")
                    .append("Action:").append(action.name()).append("\n")
                    .append("Action Input:").append(action.arguments()).append("\n")
                    .append("Observation:").append(observation.toolName()).append(":")
                    .append(boundedResult).append("\n\n");
        }
    }

    /**
     * 原生严格工具调用循环。
     *
     * 模型只负责返回 execute_tool(toolName,input) 或最终文本；工具仍由 ToolExecutor 执行。
     * 如果 Beta 端点在任意一轮失败，则把已经执行的工具结果保留在 conversation 中，从旧结构化循环继续。
     */
    private String runStrictToolCallingLoop(String systemPrompt,
                                            String fallbackSystemPrompt,
                                            StringBuilder conversation,
                                            List<AgentStep> steps,
                                            AgentPersistenceContext persistenceContext) {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(conversation.toString()));
        int iteration = 0;
        while (true) {
            iteration++;
            if (iteration > MAX_ITERATIONS) {
                return null;
            }
            log.info("严格 Function Calling 第{}轮迭代...", iteration);

            ToolCallingCallResult callResult;
            logContextBudget(systemPrompt, conversation);
            try {
                callResult = llmService.chatWithStrictToolsWithUsage(
                        systemPrompt, messages, toolRegistry.getAllTools()
                );
            } catch (RuntimeException strictException) {
                log.warn("严格 Function Calling 调用失败，从第{}轮切回结构化 ReAct：{}",
                        iteration, strictException.getMessage());
                return runStructuredLoop(
                        fallbackSystemPrompt,
                        conversation,
                        steps,
                        persistenceContext,
                        iteration - 1
                );
            }

            int stepTokenCount = calculateTokenCount(
                    callResult.promptTokens(),
                    callResult.completionTokens(),
                    callResult.totalTokens()
            );
            if (persistenceContext != null) {
                persistenceContext.totalTokens += stepTokenCount;
            }

            if (callResult.toolCalls().isEmpty()) {
                String finalAnswer = TextUtil.trimToNull(callResult.content());
                persistAgentStep(persistenceContext, iteration, "final", finalAnswer,
                        null, null, null, stepTokenCount);
                return finalAnswer;
            }

            StrictToolCall strictCall = callResult.toolCalls().get(0);
            ToolCall action = new ToolCall(strictCall.toolName(), strictCall.input());
            Observation observation = toolExecutor.execute(action);
            // 工具结果进上下文前先做体积控制（A7 / D18）：原文仍完整落 t_agent_step
            String boundedResult = boundedToolResult(observation.toolName(), observation.result());
            AgentStep step = new AgentStep(
                    iteration, null, action.name(), action.arguments(), observation.result()
            );
            steps.add(step);
            persistAgentStep(persistenceContext, iteration, "action", null,
                    action.name(), action.arguments(), observation.result(), stepTokenCount);

            // 原始 assistant tool_call 与同 ID 的 tool response 必须成对进入下一轮，不能改写成普通文本消息。
            messages.add(callResult.assistantMessage());
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            strictCall.id(), strictCall.functionName(), boundedResult
                    )))
                    .build());

            // 同步维护文本副本，只用于 Beta 失败后无损切回旧结构化循环。
            conversation.append("AI:\n")
                    .append("Action:").append(action.name()).append("\n")
                    .append("Action Input:").append(action.arguments()).append("\n")
                    .append("Observation:").append(observation.toolName()).append(":")
                    .append(boundedResult).append("\n\n");
        }
    }

    /**
     * 将摘要记忆拼接到对话上下文中，格式为 "Conversation summary:" + 摘要文本。
     * <p>
     * 摘要位于长期记忆之后、短期记忆之前。摘要是早期对话的压缩版本，让 LLM 在上下文窗口有限时
     * 仍能感知历史对话要点，而不需要加载所有原始消息。
     */
    private void appendSummary(StringBuilder conversation, String summary) {
        if (TextUtil.isBlank(summary)) {
            return;
        }
        conversation.append("Conversation summary:\n")
                .append(summary.trim())
                .append("\n\n");
    }

    /**
     * 判断当前是否启用结构化内核（阶段 5.4）。
     * <p>
     * 决策链：若 RuntimeSettingService 未注入（单测构造器场景），回退到构造器的默认值；
     * 若已注入，从运行期设置读取，方便生产环境因结构化输出异常（如模型降级、schema 不兼容）
     * 时快速切回文本路兜底，无需重启服务。
     */
    private boolean isStructuredKernelEnabled() {
        // 生产环境走运行期设置，方便结构化输出异常时快速切回文本兜底；单测构造器不强迫注入设置模块。
        return runtimeSettingService == null
                ? structuredKernelDefaultEnabled
                : runtimeSettingService.isStructuredKernelEnabled();
    }

    /**
     * 从 LLM 自由文本响应中提取 Final Answer，不存在则返回 null。
     * <p>
     * 正则匹配 "Final Answer: 正文"，使用 DOTALL 模式支持多行答案。
     * 使用 find() 而非 matches()，因为 LLM 输出可能包含其他文本（如日志、说明）在 Final Answer 行之外。
     *
     * @param text LLM 原始输出
     * @return finalAnswer 内容；未匹配到则返回 null
     */
    String parseFinalAnswer(String text) {
        Matcher m = FINAL_ANSWER.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * 从 LLM 自由文本响应中提取 Action + Action Input，两者必须同时存在才返回有效 ToolCall。
     * <p>
     * 设计约束：Action 和 Action Input 是强耦合的——缺 Action Input 意味着不知道工具参数，
     * 不应猜测默认值；缺 Action 意味着不知道调用哪个工具。任一缺失都视为解析失败，
     * 返回 null 触发格式错误回灌。
     *
     * @param text LLM 原始输出
     * @return 有效的工具调用对象；任一字段缺失则返回 null
     */
    ToolCall parseAction(String text) {
        Matcher actionMatcher = ACTION.matcher(text);
        Matcher inputMatcher = ACTION_INPUT.matcher(text);
        if (actionMatcher.find() && inputMatcher.find()) {
            return new ToolCall(actionMatcher.group(1).trim(), inputMatcher.group(1).trim());
        }
        return null;
    }

    /**
     * 从 LLM 自由文本响应中提取 Thought，未匹配到返回空串。
     * <p>
     * 返回空串而非 null：Thought 在日志和步骤追踪中是信息性字段，空串比 null 更安全，
     * 避免下游拼接时出现 "null" 字符串。
     *
     * @param text LLM 原始输出
     * @return Thought 内容；未匹配到返回 ""
     */
    private String parseThought(String text) {
        Matcher m = THOUGHT.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * 把模型的错误输出和精确格式要求一并写回对话，让下一轮知道自己错在哪里。
     *
     * 只追加笼统 ERROR 会丢失上一轮 AI 回复，模型容易在相同上下文中重复输出裸 JSON；
     * 保留原文符合正常多轮对话语义，也不会额外写入日志。
     */
    private void appendFormatCorrection(StringBuilder conversation, String llmAnswer) {
        conversation.append("AI:\n").append(llmAnswer).append("\n")
                .append("Observation:\n").append(FORMAT_ERROR_OBSERVATION).append("\n\n");
    }

    /**
     * 记忆冲突时的使用规则：长期记忆是提炼摘要，可能过期或把助手建议误记成用户事实，
     * 最近会话才是用户当前说法。规则统一追加到执行器系统提示词末尾，避免只修一条执行路径。
     */
    private static final String MEMORY_CONFLICT_RULES = """
            记忆使用规则：
            - 上下文里的 Long-term memory（长期记忆）是从历史对话提炼的摘要，可能过期，也可能把助手的建议误记成用户事实；Recent conversation（最近会话）才是用户当前的说法。
            - Recent conversation 里用户明确更正过的事实，优先于更早的说法；更正出现在后面时，即使只有一句话也优先。
            - Recent conversation 里用户说得含糊（“好像”“不确定”），而 Long-term memory 是确定值时，以长期记忆为准。
            - 助手在历史里的建议和数字不算用户事实，除非用户明确确认过。
            - Recent conversation 里以 ToolCall: 开头的 AI 消息是助手自己的工具调用（工具名 + 参数），紧跟其后的 Tool 消息是这次调用的结果；它们都是执行证据，不是用户说的话。
            - 工具调用的参数和结果里的数字、路径、错误信息可以作为执行证据引用，但不能当成用户偏好、用户身份或用户已确认的事实。
            - 两条 Long-term memory 冲突时，以内容日期更晚的为准；无法比较时不要把两者强行合并。
            - 回答前同时检查 Long-term memory、Conversation summary（摘要）和 Recent conversation，不要只看其中一处就断言信息不可用。
            """;

    private static String appendMemoryConflictRules(String systemPrompt) {
        return systemPrompt + "\n\n" + MEMORY_CONFLICT_RULES;
    }

    /**
     * 构建文本路 ReAct 的系统提示词（含 Thought/Action/Action Input/Final Answer 格式约定）。
     * <p>
     * 工具描述通过 {@link AgentToolPromptFormatter#format} 统一格式化；提示词模板从 promptService
     * 渲染，支持模板化管理和 A/B 测试。
     *
     * @param tools 当前注册的工具集合（含 RAG 的 knowledge_search）
     * @return 完整的系统提示词文本
     */
    private String buildSystemPrompt(Collection<Tool> tools) {
        String toolDescriptions = AgentToolPromptFormatter.format(tools);
        return appendMemoryConflictRules(promptService.render("agent_executor.system", Map.of("toolList", toolDescriptions)));
    }

    /**
     * 构建结构化内核的系统提示词（阶段 5.4）：列出工具 + 要求「每步要么调用工具(action+actionInput)、要么给 finalAnswer」。
     * <p>
     * 与文本路提示词的关键区别：不再写 Thought:/Action: 文本格式约定——
     * 具体 JSON schema 由 chatStructured 的 BeanOutputConverter 自动追加进 prompt 的末尾，
     * LLM 直接按 JSON 结构输出，省去正则解析环节。
     *
     * @param tools 当前注册的工具集合
     * @return 完整的结构化系统提示词文本
     */
    private String buildStructuredSystemPrompt(Collection<Tool> tools) {
        String toolDescriptions = AgentToolPromptFormatter.format(tools);
        return appendMemoryConflictRules(promptService.render("agent_executor_structured.system", Map.of("toolList", toolDescriptions)));
    }

    /** 原生 Function Calling 不再要求模型输出 ReActStep，只保留工具使用边界和最终回答要求。 */
    private String buildToolCallingSystemPrompt(Collection<Tool> tools) {
        String toolDescriptions = AgentToolPromptFormatter.format(tools);
        return appendMemoryConflictRules(promptService.render("agent_executor_tool_calling.system", Map.of("toolList", toolDescriptions)));
    }

    // ═══════════════════════════════════════════════════════════════
    // SSE 流式输出（阶段 P0-4）：在 ReAct 迭代过程中实时推送步骤事件与最终答案
    // ═══════════════════════════════════════════════════════════════

    /**
     * SSE 流式 ReAct 循环入口。
     * <p>
     * 与 {@link #run(String, String, String, AgentExecutionMode)} 的区别：
     * 不等待全部步骤完成再返回，而是在每个 ReAct 步骤完成时通过 {@link SseEmitter} 实时推送事件，
     * 最终答案以 token 粒度逐字符流式发送，让前端实现"打字机"效果的 AI 回复。
     * <p>
     * 此方法由 Controller 通过 {@code CompletableFuture.runAsync} 异步调用，
     * Controller 在提交任务后立即返回 SseEmitter，让 HTTP 连接保持为 SSE 长连接。
     *
     * @param sessionId 会话标识，为空时自动生成 UUID
     * @param userId 用户标识，为空时使用 "default"
     * @param userMessage 用户原始输入
     * @param requestedMode 调用方期望的执行模式
     * @param emitter SSE 发射器，用于向客户端推送事件
     */
    public void runStreaming(String sessionId, String userId, String userMessage,
                             AgentExecutionMode requestedMode, SseEmitter emitter) {
        runStreaming(sessionId,userId,userMessage,requestedMode,emitter,"");
    }

    /** 流式与普通请求共享同一份本轮上下文，页面事实不写入聊天历史。 */
    public void runStreaming(String sessionId, String userId, String userMessage,
                             AgentExecutionMode requestedMode, SseEmitter emitter, String requestContext) {
        requestContext=TextUtil.trimToDefault(requestContext, "");
        AgentPersistenceContext persistenceContext = null;
        // 客户端断开 / 点「停止生成」时置位：这一轮不算完成，跳过摘要与长期记忆提取（设计文档 C9 / D10）。
        AtomicBoolean cancelled = new AtomicBoolean(false);
        // 本轮是否已经处理过（含取消路径）：断开兜底据此判断要不要补写用户消息，避免写两遍。
        AtomicBoolean turnRecorded = new AtomicBoolean(false);
        String resolvedSessionId = null;
        String resolvedUserId = null;
        // 采集本轮第一次真实请求的信封，供下一轮压缩重放（流式请求同样走普通 ChatClient + tools 的形状）。
        // 计划执行成功时不会发对话请求，作用域留到计划模式之后再开启。
        ChatReplayContext.Scope replayScope = null;
        List<MemoryMessage> replayHistory = List.of();
        try {
            resolvedSessionId = resolveSessionId(sessionId);
            resolvedUserId = resolveUserId(userId);
            persistenceContext = startAgentTrace(resolvedSessionId, resolvedUserId, userMessage);

            // 容器检测到对端断开（用户点停止、刷新、关页面）时同步置位，让这一轮尽早停止写记忆（F2）。
            emitter.onError(exception -> cancelled.set(true));
            emitter.onTimeout(() -> cancelled.set(true));

            // 首先发送 session 事件，让前端知道当前会话 ID
            sendSseEvent(emitter, "session", Map.of("sessionId", resolvedSessionId));

            // 拼接记忆 + 用户输入作为对话起点（与 run() 完全一致）；超过阈值时同样先压缩
            ContextAssembly assembly = buildConversationContext(resolvedSessionId, resolvedUserId, userMessage);
            StringBuilder conversation = new StringBuilder(assembly.text());
            // 压缩会带来几秒停顿，必须发事件说明；前端据此在消息流里显示"已压缩历史"提示（DSH 式可见）。
            sendCompactionEvent(emitter, assembly.compaction());

            // 路由到正确的执行模式
            AgentExecutionMode selectedMode = executionModeRouter.route(requestedMode, userMessage);
            if (selectedMode != AgentExecutionMode.REACT) conversation.insert(0,requestContext + "\n");
            AgentRunResult plannedResult = tryRunPlannedMode(selectedMode, requestedMode, conversation.toString(), userMessage);
            if (plannedResult != null) {
                if (TextUtil.hasText(plannedResult.finalAnswer())) {
                    persistChatTurn(resolvedSessionId, resolvedUserId, userMessage, plannedResult.finalAnswer(),
                            persistenceContext.totalTokens, plannedResult.steps(), cancelled, turnRecorded);
                    // 先发送步骤追踪，让前端看到规划模式的步骤详情
                    sendPlannedSteps(emitter, plannedResult);
                    // 流式发送最终答案
                    streamTokens(emitter, plannedResult.finalAnswer());
                }
                completeAgentTrace(persistenceContext, 1, plannedResult.finalAnswer(), plannedResult.totalSteps(), null);
                sendSseEvent(emitter, "done", Map.of("sessionId", resolvedSessionId));
                emitter.complete();
                return;
            }

            // 从这里开始才真正发对话请求：开启采集作用域，记下本轮请求与当时的摘要版本。
            replayScope = ChatReplayContext.begin();
            replayScope.summary(assembly.summary());
            replayHistory = assembly.history();

            // === 结构化内核路径（阶段 5.4+） ===
            // 走结构化 schema 约束的 JSON ReAct，每步通过 chatStructured 产出 ReActStep
            if (isStructuredKernelEnabled()) {
                String structuredSystemPrompt = buildStructuredSystemPrompt(toolRegistry.getAllTools())
                        + (requestContext.isEmpty() ? "" : "\n\n"+requestContext);
                String toolCallingSystemPrompt = buildToolCallingSystemPrompt(toolRegistry.getAllTools())
                        + (requestContext.isEmpty() ? "" : "\n\n"+requestContext);
                // 对话链路优先走真流式工具循环：正文由模型边生成边推给前端。
                // 注意这里刻意不使用严格 Function Calling —— strict 只服务结构化输出
                // （LLMService.chatStructuredWithUsage 那条链），对话要的是流式增量。
                StreamingLoopResult streamed = runStreamingToolLoop(
                        toolCallingSystemPrompt,
                        structuredSystemPrompt,
                        conversation,
                        emitter,
                        persistenceContext
                );
                String structuredAnswer = streamed.answer();
                if (structuredAnswer != null) {
                    // 流式链路没有单独的 steps 列表，工具步骤由 persistAgentStep 收在 persistenceContext 里。
                    persistChatTurn(resolvedSessionId, resolvedUserId, userMessage, structuredAnswer,
                            persistenceContext.totalTokens, persistenceContext.toolSteps, cancelled, turnRecorded);
                    completeAgentTrace(persistenceContext, 1, structuredAnswer, persistenceContext.totalSteps, null);
                    // 真流式链路已经把正文逐片推完了，不能再发一遍；
                    // 回退到结构化 ReAct 时才有完整答案需要切片补发。
                    if (!streamed.contentStreamed()) {
                        streamTokens(emitter, structuredAnswer);
                    }
                } else {
                    // 迭代次数超限：不持久化，直接发送错误
                    completeAgentTrace(persistenceContext, 2, null, persistenceContext.totalSteps, "迭代次数超过上限");
                    sendSseEvent(emitter, "error", Map.of("message", "迭代次数超过上限"));
                }
            } else {
                // === 文本路径（原 ReAct 文本解析循环） ===
                String systemPrompt = buildSystemPrompt(toolRegistry.getAllTools())
                        + (requestContext.isEmpty() ? "" : "\n\n"+requestContext);
                String textAnswer = runTextLoopStreaming(systemPrompt, conversation, userMessage, emitter, persistenceContext);
                if (textAnswer != null) {
                    persistChatTurn(resolvedSessionId, resolvedUserId, userMessage, textAnswer,
                            persistenceContext.totalTokens, persistenceContext.toolSteps, cancelled, turnRecorded);
                    completeAgentTrace(persistenceContext, 1, textAnswer, persistenceContext.totalSteps, null);
                    streamTokens(emitter, textAnswer);
                } else {
                    completeAgentTrace(persistenceContext, 2, null, persistenceContext.totalSteps, "迭代次数超过上限");
                    sendSseEvent(emitter, "error", Map.of("message", "迭代次数超过上限"));
                }
            }

            sendSseEvent(emitter, "done", Map.of("sessionId", resolvedSessionId));
            emitter.complete();
        } catch (IOException e) {
            // 客户端断开连接或 SSE 发送失败：这一轮不算完成。
            // 若还没处理过这一轮，只把用户消息留在短期记忆里，下次调用时一起发给模型（设计文档 C9 / D10）。
            log.debug("SSE 连接已断开，sessionId={}", sessionId);
            // 页面已经停止生成时也要结束执行记录，避免后台历史一直显示运行中。
            completeAgentTrace(persistenceContext, 2, null,
                    persistenceContext == null ? 0 : persistenceContext.totalSteps, "客户端已中断生成");
            if (!turnRecorded.get() && resolvedSessionId != null) {
                persistCancelledTurn(resolvedSessionId, userMessage);
                turnRecorded.set(true);
            }
            try { emitter.complete(); } catch (Exception ignored) {}
        } catch (Exception e) {
            log.error("流式 Agent 执行失败", e);
            completeAgentTrace(persistenceContext, 2, null,
                    persistenceContext == null ? 0 : persistenceContext.totalSteps,
                    e.getMessage() != null ? e.getMessage() : "未知错误");
            // 尝试向客户端发送错误事件
            try {
                sendSseEvent(emitter, "error", Map.of("message", e.getMessage() != null ? e.getMessage() : "未知错误"));
            } catch (IOException ignored) {}
            emitter.completeWithError(e);
        } finally {
            // 流式轮同样保存请求信封；客户端中途断开也保留，下一轮压缩仍可能吃到已写入的前缀缓存。
            storeReplayEnvelope(resolvedSessionId, replayScope, replayHistory);
        }
    }

    /**
     * 结构化 ReAct 流式循环：mirror {@link #runStructuredLoop}，但每完成一个步骤就通过 SSE 推送事件。
     * <p>
     * 与 runStructuredLoop 的逻辑完全一致，唯一的区别是在工具执行后立即发送 {@code step} SSE 事件，
     * 而不是只记录下来等全部结束后再返回。
     *
     * @param systemPrompt 结构化内核的系统提示词
     * @param conversation 对话上下文字符串（原地修改）
     * @param emitter SSE 发射器
     * @return 最终答案文本；超出迭代上限返回 null
     */
    private String runStructuredLoopStreaming(String systemPrompt, StringBuilder conversation, SseEmitter emitter,
                                               AgentPersistenceContext persistenceContext)
            throws IOException {
        return runStructuredLoopStreaming(systemPrompt, conversation, emitter, persistenceContext, 0);
    }

    private String runStructuredLoopStreaming(String systemPrompt, StringBuilder conversation, SseEmitter emitter,
                                               AgentPersistenceContext persistenceContext, int initialIteration)
            throws IOException {
        int iteration = initialIteration;
        while (true) {
            iteration++;
            if (iteration > MAX_ITERATIONS) {
                return null;
            }
            log.info("流式-结构化 ReAct 第{}轮迭代...", iteration);

            logContextBudget(systemPrompt, conversation);
            StructuredCallResult<ReActStep> callResult =
                    llmService.chatStructuredWithUsage(systemPrompt, conversation.toString(), ReActStep.class);
            ReActStep step = callResult.entity();
            int stepTokenCount = calculateTokenCount(
                    callResult.promptTokens(),
                    callResult.completionTokens(),
                    callResult.totalTokens()
            );
            persistenceContext.totalTokens += stepTokenCount;

            // 优先终止：拿到非空 finalAnswer 即结束（最终答案在外面流式发送）
            if (step.isFinal()) {
                persistAgentStep(persistenceContext, iteration, "final", step.finalAnswer(), null, null, null, stepTokenCount);
                return step.finalAnswer();
            }

            // 合法 JSON 但既无 finalAnswer 也无 action：回喂错误让模型补齐
            if (!step.hasAction()) {
                sendSseEvent(emitter, "step", new AgentStep(iteration, step.thought(), null, null, null));
                persistAgentStep(persistenceContext, iteration, "thought", step.thought(), null, null, null, stepTokenCount);
                conversation.append("ERROR：既未给出 finalAnswer 也未给出 action，请重试！\n\n");
                continue;
            }

            // 执行工具
            ToolCall action = new ToolCall(step.action(), step.actionInput() == null ? "" : step.actionInput());
            Observation observation = toolExecutor.execute(action);
            // 工具结果进上下文前先做体积控制（A7 / D18）：原文仍完整落 t_agent_step
            String boundedResult = boundedToolResult(observation.toolName(), observation.result());
            // 立即推送步骤事件给前端
            sendSseEvent(emitter, "step", new AgentStep(iteration, step.thought(), action.name(), action.arguments(), observation.result()));
            persistAgentStep(persistenceContext, iteration, "action", step.thought(),
                    action.name(), action.arguments(), observation.result(), stepTokenCount);

            // 对话历史回灌
            conversation.append("AI:\n").append("Thought:").append(TextUtil.trimToDefault(step.thought(), "")).append("\n")
                    .append("Action:").append(action.name()).append("\n")
                    .append("Action Input:").append(action.arguments()).append("\n")
                    .append("Observation:").append(observation.toolName()).append(":")
                    .append(boundedResult).append("\n\n");
        }
    }

    /**
     * 对话链路的真流式工具循环。
     *
     * <p>与 {@link #runStrictToolCallingLoopStreaming} 的区别只有一个：模型调用换成流式，
     * 正文与思考内容在生成过程中就通过 SSE 推给前端，而不是等整段答案生成完再切片补发。
     * 工具调用仍按原生 tool_calls 走，工具执行、步骤落库、消息累积的逻辑保持一致。
     *
     * <p><b>为什么对话不走严格 Function Calling：</b>strict 是给结构化输出用的
     * （{@link com.link.linkagent.llm.LLMService#chatStructuredWithUsage} 那条链），
     * 它要求拿到完整响应再校验；对话要的是增量。两者用同一套调用会互相牵制，
     * 所以这里走普通 Function Calling 的流式接口，结构化输出的 strict 保证不受影响。
     *
     * <p>失败时回退到结构化 ReAct，保证「流式不可用」不会变成「对话不可用」。
     */
    private StreamingLoopResult runStreamingToolLoop(String systemPrompt,
                                                     String fallbackSystemPrompt,
                                                     StringBuilder conversation,
                                                     SseEmitter emitter,
                                                     AgentPersistenceContext persistenceContext) throws IOException {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(conversation.toString()));
        int iteration = 0;
        while (true) {
            iteration++;
            if (iteration > MAX_ITERATIONS) {
                return new StreamingLoopResult(null, false);
            }
            log.info("流式-对话工具循环第{}轮迭代...", iteration);

            ToolCallingCallResult callResult;
            logContextBudget(systemPrompt, conversation);
            try {
                callResult = llmService.streamWithToolsWithUsage(
                        systemPrompt,
                        messages,
                        toolRegistry.getAllTools(),
                        delta -> sendTokenDelta(emitter, delta),
                        delta -> sendThinkingDelta(emitter, delta),
                        // 第二轮起必须关闭思考：Spring AI 1.1.4 不会把 reasoning_content 写回下一轮请求，
                        // 多轮工具调用继续开启思考会违反 DeepSeek 协议（与严格链路同一处理）。
                        // 首轮保留配置的思考设置，这样模型返回思考内容时前端才有东西可展示。
                        iteration > 1
                );
            } catch (UncheckedIOException sendFailure) {
                // SSE 发送失败说明客户端已断开，按断开处理，不要回退重跑一遍
                throw sendFailure.getCause();
            } catch (RuntimeException callFailure) {
                log.warn("流式工具循环调用失败，从第{}轮切回结构化 ReAct：{}", iteration, callFailure.getMessage());
                String fallback = runStructuredLoopStreaming(
                        fallbackSystemPrompt, conversation, emitter, persistenceContext, iteration - 1);
                return new StreamingLoopResult(fallback, false);
            }

            int stepTokenCount = calculateTokenCount(
                    callResult.promptTokens(), callResult.completionTokens(), callResult.totalTokens());
            persistenceContext.totalTokens += stepTokenCount;

            if (callResult.toolCalls().isEmpty()) {
                String finalAnswer = TextUtil.trimToNull(callResult.content());
                persistAgentStep(persistenceContext, iteration, "final", finalAnswer,
                        null, null, null, stepTokenCount);
                // 正文已经在流式回调里逐片推完了
                return new StreamingLoopResult(finalAnswer, true);
            }

            StrictToolCall strictCall = callResult.toolCalls().get(0);
            ToolCall action = new ToolCall(strictCall.toolName(), strictCall.input());
            Observation observation = toolExecutor.execute(action);
            // 工具结果进上下文前先做体积控制（A7 / D18）：原文仍完整落 t_agent_step
            String boundedResult = boundedToolResult(observation.toolName(), observation.result());
            AgentStep step = new AgentStep(
                    iteration, null, action.name(), action.arguments(), observation.result()
            );
            sendSseEvent(emitter, "step", step);
            persistAgentStep(persistenceContext, iteration, "action", null,
                    action.name(), action.arguments(), observation.result(), stepTokenCount);

            messages.add(callResult.assistantMessage());
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            strictCall.id(), strictCall.functionName(), boundedResult
                    )))
                    .build());
            conversation.append("AI:\n")
                    .append("Action:").append(action.name()).append("\n")
                    .append("Action Input:").append(action.arguments()).append("\n")
                    .append("Observation:").append(observation.toolName()).append(":")
                    .append(boundedResult).append("\n\n");
        }
    }

    /**
     * 推送正文增量。
     * IOException 是受检异常，进不了响应式回调，这里包成非受检异常穿出去，
     * 在循环里解包还原，保证「客户端断开」仍然走原有的断开处理分支。
     */
    private void sendTokenDelta(SseEmitter emitter, String delta) {
        try {
            sendSseEvent(emitter, "token", delta);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** 推送思考增量；模型不返回思考内容时这个方法一次都不会被调用。 */
    private void sendThinkingDelta(SseEmitter emitter, String delta) {
        try {
            sendSseEvent(emitter, "thinking", delta);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 流式工具循环的结果。
     *
     * @param answer          最终答案；null 表示超出迭代上限
     * @param contentStreamed 正文是否已在生成过程中推给前端。为 true 时外层不能再补发一遍，
     *                        为 false 说明走的是回退链路，需要按老办法切片补发。
     */
    private record StreamingLoopResult(String answer, boolean contentStreamed) {
    }

    /**
     * 流式入口复用同一原生消息协议；每次工具完成后仍按现有 SSE step 事件向前端推送。
     *
     * <p><b>注意：对话链路已不再走这里。</b>对话要的是真流式，而严格 Function Calling
     * 必须拿到完整响应才能校验，两者不兼容，所以 {@code runStreaming} 改走
     * {@link #runStreamingToolLoop}。本方法暂时保留，是因为它仍是「strict + 流式循环」
     * 的完整实现，需要回到 strict 路径时可以整体切回；确认长期不用后可删除。
     */
    private String runStrictToolCallingLoopStreaming(String systemPrompt,
                                                     String fallbackSystemPrompt,
                                                     StringBuilder conversation,
                                                     SseEmitter emitter,
                                                     AgentPersistenceContext persistenceContext)
            throws IOException {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(conversation.toString()));
        int iteration = 0;
        while (true) {
            iteration++;
            if (iteration > MAX_ITERATIONS) {
                return null;
            }
            log.info("流式-严格 Function Calling 第{}轮迭代...", iteration);

            ToolCallingCallResult callResult;
            logContextBudget(systemPrompt, conversation);
            try {
                callResult = llmService.chatWithStrictToolsWithUsage(
                        systemPrompt, messages, toolRegistry.getAllTools()
                );
            } catch (RuntimeException strictException) {
                log.warn("流式严格 Function Calling 调用失败，从第{}轮切回结构化 ReAct：{}",
                        iteration, strictException.getMessage());
                return runStructuredLoopStreaming(
                        fallbackSystemPrompt,
                        conversation,
                        emitter,
                        persistenceContext,
                        iteration - 1
                );
            }

            int stepTokenCount = calculateTokenCount(
                    callResult.promptTokens(),
                    callResult.completionTokens(),
                    callResult.totalTokens()
            );
            persistenceContext.totalTokens += stepTokenCount;

            if (callResult.toolCalls().isEmpty()) {
                String finalAnswer = TextUtil.trimToNull(callResult.content());
                persistAgentStep(persistenceContext, iteration, "final", finalAnswer,
                        null, null, null, stepTokenCount);
                return finalAnswer;
            }

            StrictToolCall strictCall = callResult.toolCalls().get(0);
            ToolCall action = new ToolCall(strictCall.toolName(), strictCall.input());
            Observation observation = toolExecutor.execute(action);
            // 工具结果进上下文前先做体积控制（A7 / D18）：原文仍完整落 t_agent_step
            String boundedResult = boundedToolResult(observation.toolName(), observation.result());
            AgentStep step = new AgentStep(
                    iteration, null, action.name(), action.arguments(), observation.result()
            );
            sendSseEvent(emitter, "step", step);
            persistAgentStep(persistenceContext, iteration, "action", null,
                    action.name(), action.arguments(), observation.result(), stepTokenCount);

            messages.add(callResult.assistantMessage());
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            strictCall.id(), strictCall.functionName(), boundedResult
                    )))
                    .build());
            conversation.append("AI:\n")
                    .append("Action:").append(action.name()).append("\n")
                    .append("Action Input:").append(action.arguments()).append("\n")
                    .append("Observation:").append(observation.toolName()).append(":")
                    .append(boundedResult).append("\n\n");
        }
    }

    /**
     * 文本路 ReAct 流式循环：mirror run() 中的文本 while 循环，但每完成一个步骤就通过 SSE 推送事件。
     *
     * @param systemPrompt 文本路 ReAct 系统提示词
     * @param conversation 对话上下文字符串（原地修改）
     * @param userMessage 用户原始输入（用于持久化）
     * @param emitter SSE 发射器
     * @return 最终答案文本；超出迭代上限或格式持续异常时返回 null
     */
    private String runTextLoopStreaming(String systemPrompt, StringBuilder conversation, String userMessage,
                                        SseEmitter emitter, AgentPersistenceContext persistenceContext) throws IOException {
        int iteration = 0;
        while (true) {
            iteration++;
            if (iteration > MAX_ITERATIONS) {
                return null;
            }
            log.info("流式-文本 ReAct 第{}轮迭代...", iteration);

            // 1. 调用 LLM，获得完整响应
            logContextBudget(systemPrompt, conversation);
            LlmCallResult llmResult = llmService.chatWithUsage(systemPrompt, conversation.toString());
            String llmAnswer = llmResult.content();
            int stepTokenCount = calculateTokenCount(llmResult.promptTokens(), llmResult.completionTokens(), llmResult.totalTokens());
            persistenceContext.totalTokens += stepTokenCount;
            // 2. 优先检测 Final Answer
            String finalAnswer = parseFinalAnswer(llmAnswer);
            if (TextUtil.hasText(finalAnswer)) {
                log.info("流式-第{}轮解析到 finalAnswer", iteration);
                persistAgentStep(persistenceContext, iteration, "final", finalAnswer, null, null, null, stepTokenCount);
                return finalAnswer;
            }

            // 3. 提取 Thought
            String thought = parseThought(llmAnswer);
            if (TextUtil.isBlank(thought)) {
                // 格式错误：回喂错误让 LLM 重试，同时推送错误步骤
                AgentStep errorStep = new AgentStep(iteration, "未解析到 Thought，模型可能没有按提示词要求输出。",
                        null, null, FORMAT_ERROR_OBSERVATION);
                sendSseEvent(emitter, "step", errorStep);
                persistAgentStep(persistenceContext, iteration, "observation",
                        "未解析到 Thought，模型可能没有按提示词要求输出。",
                        null, null, FORMAT_ERROR_OBSERVATION, stepTokenCount);
                appendFormatCorrection(conversation, llmAnswer);
                continue;
            }

            // 4. 尝试解析 Action + Action Input
            ToolCall action = parseAction(llmAnswer);
            if (action == null) {
                // 无合法 Action：回喂格式错误
                sendSseEvent(emitter, "step", new AgentStep(iteration, thought, null, null, null));
                persistAgentStep(persistenceContext, iteration, "thought", thought, null, null, null, stepTokenCount);
                appendFormatCorrection(conversation, llmAnswer);
                continue;
            }

            // 5. 执行工具，得到 Observation
            Observation observation = toolExecutor.execute(action);
            // 工具结果进上下文前先做体积控制（A7 / D18）：原文仍完整落 t_agent_step
            String boundedResult = boundedToolResult(observation.toolName(), observation.result());
            // 立即推送步骤事件给前端
            sendSseEvent(emitter, "step", new AgentStep(iteration, thought, action.name(), action.arguments(), observation.result()));
            persistAgentStep(persistenceContext, iteration, "action", thought,
                    action.name(), action.arguments(), observation.result(), stepTokenCount);

            // 6. 将本轮的 Thought/Action/Observation 拼回对话
            conversation.append("AI:\n").append("Thought:").append(thought).append("\n")
                    .append("Action:").append(action.name()).append("\n")
                    .append("Action Input:").append(action.arguments()).append("\n")
                    .append("Observation:").append(observation.toolName()).append(":")
                    .append(boundedResult).append("\n\n");
        }
    }

    /**
     * 将规划模式的步骤推送给前端。
     * <p>
     * 规划模式（Plan & Execute / Multi-Agent）产生的 AgentRunResult 包含 steps 字段，
     * 这里遍历这些步骤通过 SSE 逐一推送，让前端在拿到最终答案前就能看到规划链路的执行细节。
     */
    private void sendPlannedSteps(SseEmitter emitter, AgentRunResult result) throws IOException {
        if (result.steps() == null || result.steps().isEmpty()) {
            return;
        }
        for (AgentStep step : result.steps()) {
            sendSseEvent(emitter, "step", step);
        }
    }

    /**
     * 将最终答案以 token 粒度逐字符通过 SSE 流式发送，模拟打字效果。
     * <p>
     * 对中文文本（CJK 统一表意文字）按单字符切分，因为每个汉字本身就是语义单元；
     * 对英文/数字按 2-4 个字符一组切分，在视觉上形成流畅的"逐字打印"效果。
     * <p>
     * 设计权衡：这里对已生成的完整文本做"伪流式"切分，而非真正从 LLM token 级别流式输出，
     * 原因是 ReAct 循环需要完整解析 LLM 响应后才能判断是工具调用还是最终答案——
     * 无法在流式过程中提前知道当前响应是否包含 Final Answer。等后续 Spring AI 成熟度提升后，
     * 可以改为直接流式转发 LLM 的原始 token。
     *
     * @param emitter SSE 发射器
     * @param text 要流式发送的文本
     */
    private void streamTokens(SseEmitter emitter, String text) throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 对 CJK 字符（中文/日文/韩文）单字符发送；对英文/数字等每次发送 2-4 个字符一组
            if (isCjkCharacter(c)) {
                sendSseEvent(emitter, "token", String.valueOf(c));
            } else {
                // 累积连续的非 CJK 字符（如英文单词、数字、标点），按小段发送
                int end = i;
                while (end < text.length() && !isCjkCharacter(text.charAt(end)) && (end - i) < 3) {
                    end++;
                }
                sendSseEvent(emitter, "token", text.substring(i, end));
                i = end - 1; // for 循环会再 +1
            }
        }
    }

    /**
     * 判断字符是否为 CJK 统一表意文字（包括中文汉字、日文汉字、韩文汉字）。
     * <p>
     * Unicode 范围：
     * <ul>
     *   <li>CJK 统一表意文字：U+4E00–U+9FFF</li>
     *   <li>CJK 扩展 A：U+3400–U+4DBF（罕用汉字）</li>
     *   <li>CJK 兼容表意文字：U+F900–U+FAFF</li>
     * </ul>
     */
    private boolean isCjkCharacter(char c) {
        return (c >= '一' && c <= '鿿')
                || (c >= '㐀' && c <= '䶿')
                || (c >= '豈' && c <= '﫿');
    }

    /**
     * 向 SseEmitter 发送一个命名事件。
     * <p>
     * 数据对象会被 Spring 自动序列化为 JSON（通过 Jackson）。
     * 如果发送失败（客户端断开连接），抛出 IOException 让上层统一处理。
     *
     * @param emitter SSE 发射器
     * @param name 事件名称（前端通过 addEventListener 监听）
     * @param data 事件数据，会被序列化为 JSON
     * @throws IOException 当前端连接断开或 SSE 写入失败时抛出
     */
    private static void sendSseEvent(SseEmitter emitter, String name, Object data) throws IOException {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IllegalStateException exception) {
            // 用户在界面上点了「停止生成」或刷新后，这一轮 SSE 已经收尾/断开，再 send 会抛
            // "ResponseBodyEmitter has already completed"。它和客户端断开是同一类问题（对端不在了），
            // 统一转成 IOException 交给 runStreaming 的断开分支，避免刷一屏 ERROR 堆栈盖住真正的错误
            // （2026-09-19 作者实测日志里就是这条）。
            throw new IOException("SSE 连接已结束：" + exception.getMessage(), exception);
        }
    }
}
