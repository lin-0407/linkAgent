package com.link.linkagent.core;

import com.link.linkagent.dto.AgentChatResponse;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.memory.LongTermMemory;
import com.link.linkagent.core.multi.MultiAgentOrchestrator;
import com.link.linkagent.core.plan.PlanAndExecuteAgent;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.memory.LongTermMemoryCandidate;
import com.link.linkagent.memory.LongTermMemoryExtractor;
import com.link.linkagent.memory.LongTermMemoryRecord;
import com.link.linkagent.memory.MemoryMessage;
import com.link.linkagent.memory.ShortTermMemory;
import com.link.linkagent.memory.SummaryMemory;
import com.link.linkagent.tool.Tool;
import com.link.linkagent.tool.ToolExecutor;
import com.link.linkagent.tool.ToolRegistry;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.util.TextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final LongTermMemoryExtractor longTermMemoryExtractor;
    private final PromptService promptService;
    private final RuntimeSettingService runtimeSettingService;
    private final AgentExecutionModeRouter executionModeRouter;
    private final PlanAndExecuteAgent planAndExecuteAgent;
    private final MultiAgentOrchestrator multiAgentOrchestrator;
    /** 生产环境从运行期设置读取结构化开关；单测不注入设置服务时回退到构造器默认值。 */
    private final boolean structuredKernelDefaultEnabled;

    /**
     * ReAct 最大迭代次数。防止 LLM 陷入死循环或工具调用递归耗尽资源（Token 成本 + CPU 时间）。
     * 10 次对大多数任务够用——常规问题 2~4 轮解决，复杂多步推理很少超过 8 轮。
     */
    private static final int MAX_ITERATIONS = 10;

    /**
     * 格式错误时的标准 Observation 文本。当 LLM 未按 Thought/Action/Action Input 格式输出时，
     * 将此文本作为 Observation 回灌到对话中，让 LLM 看到错误后自行修正下一步输出。
     */
    private static final String FORMAT_ERROR_OBSERVATION =
            "LLM 本轮输出未遵守 ReAct 文本格式，已回传格式错误提示并要求模型重试。";

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
                         MultiAgentOrchestrator multiAgentOrchestrator) {
        this(llmService, toolRegistry, toolExecutor, shortTermMemory, summaryMemory, longTermMemory,
                longTermMemoryExtractor, promptService, runtimeSettingService, true,
                executionModeRouter, planAndExecuteAgent, multiAgentOrchestrator);
    }

    public AgentExecutor(LLMService llmService, ToolRegistry toolRegistry, ToolExecutor toolExecutor,
                         ShortTermMemory shortTermMemory,
                         SummaryMemory summaryMemory, LongTermMemory longTermMemory,
                         LongTermMemoryExtractor longTermMemoryExtractor,
                         PromptService promptService) {
        this(llmService, toolRegistry, toolExecutor, shortTermMemory, summaryMemory, longTermMemory,
                longTermMemoryExtractor, promptService, null, false,
                new AgentExecutionModeRouter(), null, null);
    }

    AgentExecutor(LLMService llmService, ToolRegistry toolRegistry, ToolExecutor toolExecutor,
                  ShortTermMemory shortTermMemory,
                  SummaryMemory summaryMemory, LongTermMemory longTermMemory,
                  LongTermMemoryExtractor longTermMemoryExtractor,
                  PromptService promptService,
                  boolean structuredKernelDefaultEnabled) {
        this(llmService, toolRegistry, toolExecutor, shortTermMemory, summaryMemory, longTermMemory,
                longTermMemoryExtractor, promptService, null, structuredKernelDefaultEnabled,
                new AgentExecutionModeRouter(), null, null);
    }

    private AgentExecutor(LLMService llmService, ToolRegistry toolRegistry, ToolExecutor toolExecutor,
                          ShortTermMemory shortTermMemory,
                          SummaryMemory summaryMemory, LongTermMemory longTermMemory,
                          LongTermMemoryExtractor longTermMemoryExtractor,
                          PromptService promptService,
                          RuntimeSettingService runtimeSettingService,
                          boolean structuredKernelDefaultEnabled,
                          AgentExecutionModeRouter executionModeRouter,
                          PlanAndExecuteAgent planAndExecuteAgent,
                          MultiAgentOrchestrator multiAgentOrchestrator) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.shortTermMemory = shortTermMemory;
        this.summaryMemory = summaryMemory;
        this.longTermMemory = longTermMemory;
        this.longTermMemoryExtractor = longTermMemoryExtractor;
        this.promptService = promptService;
        this.runtimeSettingService = runtimeSettingService;
        this.structuredKernelDefaultEnabled = structuredKernelDefaultEnabled;
        this.executionModeRouter = executionModeRouter == null ? new AgentExecutionModeRouter() : executionModeRouter;
        this.planAndExecuteAgent = planAndExecuteAgent;
        this.multiAgentOrchestrator = multiAgentOrchestrator;
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
        String resolvedSessionId = resolveSessionId(sessionId);
        String resolvedUserId = resolveUserId(userId);

        // 拼接记忆 + 用户输入作为对话起点，格式与系统提示词约定一致
        StringBuilder conversation = new StringBuilder();
        appendLongTermMemory(conversation, longTermMemory.listByUser(resolvedUserId, 10));
        appendSummary(conversation, summaryMemory.getSummary(resolvedSessionId));
        List<MemoryMessage> recentMessages = shortTermMemory.getRecentMessages(resolvedSessionId);
        appendMemory(conversation, recentMessages);
        conversation.append("Human:").append(userMessage).append("\n\n");

        AgentExecutionMode selectedMode = executionModeRouter.route(requestedMode, userMessage);
        AgentRunResult plannedResult = tryRunPlannedMode(selectedMode, requestedMode, conversation.toString(), userMessage);
        if (plannedResult != null) {
            if (TextUtil.hasText(plannedResult.finalAnswer())) {
                persistChatTurn(resolvedSessionId, resolvedUserId, userMessage, plannedResult.finalAnswer());
            }
            return toChatResponse(resolvedSessionId, plannedResult);
        }

        // === 结构化内核路径（阶段 5.4+） ===
        // 每步通过 chatStructured 产出受 JSON Schema 约束的 ReActStep，省去正则解析，降低格式错误率。
        // 开关由 RuntimeSettingService 控制，方便生产环境出现结构化输出异常时快速切回文本路兜底。
        if (isStructuredKernelEnabled()) {
            String structuredSystemPrompt = buildStructuredSystemPrompt(toolRegistry.getAllTools());
            List<AgentStep> structuredSteps = new ArrayList<>();
            String structuredAnswer = runStructuredLoop(structuredSystemPrompt, conversation, structuredSteps);
            if (structuredAnswer == null) {
                // 迭代次数超限：不持久化任何记忆，因为未拿到有效答案，避免脏数据污染记忆层
                return new AgentChatResponse(resolvedSessionId, null, "迭代次数超过上限", structuredSteps.size(), structuredSteps);
            }
            persistChatTurn(resolvedSessionId, resolvedUserId, userMessage, structuredAnswer);
            return new AgentChatResponse(resolvedSessionId, structuredAnswer, null, structuredSteps.size(), structuredSteps);
        }

        // === 文本路径（原 ReAct 文本解析循环，逻辑保持不变） ===
        // 走"自由文本 LLM 输出 → 正则提取 Thought/Action/Final Answer"的传统 ReAct 路径。
        // 兜底保障：即使结构化内核关闭或异常，核心 Agent 循环仍可正常工作。
        String systemPrompt = buildSystemPrompt(toolRegistry.getAllTools());
        String finalAnswer = null;
        int iteration = 0;
        List<AgentStep> steps = new ArrayList<>();
        while(true){
            iteration++;

            // 迭代上限兜底：防止无限循环耗尽资源 (Token 费用 + CPU 时间 + 连接池)
            // 超过 MAX_ITERATIONS 说明 LLM 无法在给定轮次内完成推理，可能是任务本身不可解或提示词有缺陷
            if(iteration > MAX_ITERATIONS){
                return new AgentChatResponse(resolvedSessionId, finalAnswer, "迭代次数超过上限", steps.size(), steps);
            }

            log.info("正在进行第{}轮ReAct迭代...", iteration);

            // 1. 调用 LLM，传入完整对话历史（含之前所有轮次的 Thought/Action/Observation）
            String llmAnswer = llmService.chat(systemPrompt, conversation.toString());
            log.info("第{}轮LLM原始响应:\n{}", iteration, llmAnswer);

            // 2. 优先检测 Final Answer（即使同时存在 Action 也以 Final Answer 为准）
            // 设计权衡：Final Answer 优先级最高，因为它是推理链的最终产物。
            // 如果 LLM 同时输出了 Action 和 Final Answer，取 Final Answer 直接终止——宁可损失一步工具调用，
            // 也不让用户在答案已经生成的情况下继续等待多余的推理轮次。
            finalAnswer = parseFinalAnswer(llmAnswer);
            if (TextUtil.hasText(finalAnswer)) {
                log.info("第{}轮解析结果: thought={}, finalAnswer={}", iteration, parseThought(llmAnswer), finalAnswer);
                // Final Answer 拿到即持久化：短期记忆写 Human + AI 对、按需触发摘要、抽取长期记忆
                persistChatTurn(resolvedSessionId, resolvedUserId, userMessage, finalAnswer);
                return new AgentChatResponse(resolvedSessionId, finalAnswer, null, steps.size(), steps);
            }

            // 3. 提取 Thought — 必须存在，否则说明 LLM 未按提示词格式输出，需要回喂错误让 LLM 重试
            String thought = parseThought(llmAnswer);
            if (TextUtil.isBlank(thought)) {
                // LLM 既未给出 Final Answer 也未给出合法 Thought：本轮输出完全不可用
                log.warn("第{}轮未解析到合法 Thought，rawResponse={}", iteration, llmAnswer);
                // 记录本轮为格式错误步骤：Thought 设为错误描述，Observation 设为标准格式错误提示
                // 这样在步骤追踪中可以看到 LLM 在哪一轮输出了不合法内容
                steps.add(new AgentStep(iteration, "未解析到 Thought，模型可能没有按提示词要求输出。", null, null,
                        FORMAT_ERROR_OBSERVATION));
                conversation.append("ERROR：输出格式错误，请严格按照格式输出！\n\n");
                continue;
            }
            log.info("第{}轮解析到 Thought: {}", iteration, thought);

            // 4. 尝试解析 Action + Action Input（两者必须同时存在才构成有效工具调用）
            // 设计权衡：Action 和 Action Input 分开提取但要求同时非空。
            // 若仅 Action 存在而 Action Input 缺失，说明 LLM 输出不完整——不应猜测参数默认值，
            // 因为给工具的默认值很可能不是 LLM 的意图，执行后产生错误 Observation 会误导后续推理。
            ToolCall action = parseAction(llmAnswer);
            if(action == null){
                // LLM 既未给出 Final Answer 也未给出合法 Action：本轮输出不完整，回喂错误让 LLM 重试
                log.warn("第{}轮未解析到合法 Action，rawResponse={}", iteration, llmAnswer);
                steps.add(new AgentStep(iteration, thought, null, null, null));
                conversation.append("ERROR：输出格式错误，请严格按照格式输出！\n\n");
                continue;
            }
            log.info("第{}轮解析到 Action: {}, Action Input: {}", iteration, action.name(), action.arguments());

            // 5. 执行工具，得到 Observation
            // ToolExecutor 内部负责校验工具是否存在、参数是否合法，并捕获工具执行异常返回错误 Observation
            Observation observation = toolExecutor.execute(action);
            log.info("第{}轮工具执行结果: tool={}, observation={}", iteration, observation.toolName(), observation.result());
            steps.add(new AgentStep(iteration, thought, action.name(), action.arguments(), observation.result()));

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
                    .append(observation.result())
                    .append("\n\n");
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
            String structuredAnswer = runStructuredLoop(structuredSystemPrompt, structuredConversation, structuredSteps);
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
            log.info("任务模式 ReAct 第{}轮迭代...", iteration);

            String llmAnswer = llmService.chat(systemPrompt, conversation.toString());

            // 优先检测 Final Answer：拿到自由文本结论即终止
            // 全程不写任何记忆（这是与 run 文本路的核心区别），因为任务推理是一次性中间步骤
            finalAnswer = parseFinalAnswer(llmAnswer);
            if (TextUtil.hasText(finalAnswer)) {
                return new AgentChatResponse(null, finalAnswer, null, steps.size(), steps);
            }

            String thought = parseThought(llmAnswer);
            if (TextUtil.isBlank(thought)) {
                // 既无 Final Answer 也无合法 Thought：回喂格式错误让 LLM 重试（与 run 处理一致）
                steps.add(new AgentStep(iteration, "未解析到 Thought，模型可能没有按提示词要求输出。", null, null,
                        FORMAT_ERROR_OBSERVATION));
                conversation.append("ERROR：输出格式错误，请严格按照格式输出！\n\n");
                continue;
            }

            ToolCall action = parseAction(llmAnswer);
            if (action == null) {
                // 无合法 Action：回喂格式错误让 LLM 重试
                steps.add(new AgentStep(iteration, thought, null, null, null));
                conversation.append("ERROR：输出格式错误，请严格按照格式输出！\n\n");
                continue;
            }

            Observation observation = toolExecutor.execute(action);
            steps.add(new AgentStep(iteration, thought, action.name(), action.arguments(), observation.result()));

            // 将本轮 Thought/Action/Observation 拼回对话，供下一轮参考（拼接格式与 run 一致）
            conversation.append("AI:\n").append("Thought:").append(thought).append("\n")
                    .append("Action:").append(action.name()).append("\n")
                    .append("Action Input:").append(action.arguments()).append("\n")
                    .append("Observation:").append(observation.toolName()).append(":")
                    .append(observation.result()).append("\n\n");
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
            return supplier.get();
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
     * listByUser 每次取最近 10 条，平衡了记忆覆盖面和 Token 成本。
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
     * 将短期记忆（最近 N 轮对话）拼接到对话上下文中，格式为 "Recent conversation:" + 多行 "role:content"。
     * <p>
     * 短期记忆位于摘要之后、用户消息之前，为 LLM 提供最直接的对话连续性上下文。
     * 当摘要触发后，短期记忆只保留最近 N 条消息（由 summaryMemory.getRetainedMessageCount 控制），
     * 更早的消息被摘要压缩，避免上下文膨胀。
     */
    private void appendMemory(StringBuilder conversation, List<MemoryMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        conversation.append("Recent conversation:\n");
        for (MemoryMessage message : messages) {
            conversation.append(message.role())
                    .append(":")
                    .append(message.content())
                    .append("\n");
        }
        conversation.append("\n");
    }

    /**
     * 尝试从本轮对话中提取长期记忆并保存。
     * <p>
     * 调用 LongTermMemoryExtractor（内部通过 LLM 判断是否值得记忆），仅在 extractor 返回非空
     * candidate 时才实际写入数据库。extract 返回 Optional.empty 表示"本轮对话无值得记录的信息"，
     * 此时不做任何持久化操作，避免存储噪音数据。
     */
    private void tryExtractAndSaveLongTermMemory(String userId, String sessionId, String userMessage, String finalAnswer) {
        longTermMemoryExtractor.extract(userMessage, finalAnswer)
                .ifPresent(candidate -> saveLongTermMemory(userId, sessionId, candidate));
    }

    /**
     * 将提取的长期记忆候选写入持久化存储。
     * <p>
     * 异常被静默捕获且仅打 warn 日志——长期记忆保存失败不应中断主流程的 Final Answer 返回。
     * 用户已经得到答案，因为持久化失败而丢答案是不可接受的用户体验。
     */
    private void saveLongTermMemory(String userId, String sessionId, LongTermMemoryCandidate candidate) {
        try {
            longTermMemory.save(userId, candidate.memoryKey(), candidate.content(), sessionId);
            log.info("长期记忆已自动保存，userId={}, memoryKey={}", userId, candidate.memoryKey());
        } catch (Exception exception) {
            log.warn("长期记忆保存失败，userId={}, memoryKey={}, error={}",
                    userId, candidate.memoryKey(), exception.getMessage());
        }
    }

    /**
     * 持久化一次「面向用户的聊天回合」：写短期记忆 + 按需触发摘要 + 抽取长期记忆。
     * 抽成方法是因为文本路与结构化路（5.4）在拿到 finalAnswer 后都要做同一件事，避免两处重复。
     */
    private void persistChatTurn(String sessionId, String userId, String userMessage, String finalAnswer) {
        shortTermMemory.append(sessionId, "Human", userMessage);
        shortTermMemory.append(sessionId, "AI", finalAnswer);
        if (summaryMemory.trySummarize(sessionId, shortTermMemory.getRecentMessages(sessionId))) {
            shortTermMemory.keepRecentMessages(sessionId, summaryMemory.getRetainedMessageCount());
            log.info("摘要记忆已达到触发条件，sessionId={}", sessionId);
        }
        tryExtractAndSaveLongTermMemory(userId, sessionId, userMessage, finalAnswer);
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
        int iteration = 0;
        while (true) {
            iteration++;
            // 迭代上限兜底：与文本路一致
            if (iteration > MAX_ITERATIONS) {
                return null;
            }
            log.info("结构化 ReAct 第{}轮迭代...", iteration);

            // chatStructured 内部已对解析失败重试；仍失败则抛出（含成本 guard 的 400），交上层按错误返回，不在此吞掉
            ReActStep step = llmService.chatStructured(systemPrompt, conversation.toString(), ReActStep.class);

            // 优先终止：拿到非空 finalAnswer 即结束
            if (step.isFinal()) {
                return step.finalAnswer();
            }

            // 合法 JSON 但既无 finalAnswer 也无 action：回喂错误让模型补齐（与文本路格式错误同构）
            if (!step.hasAction()) {
                steps.add(new AgentStep(iteration, step.thought(), null, null, null));
                conversation.append("ERROR：既未给出 finalAnswer 也未给出 action，请重试！\n\n");
                continue;
            }

            // 执行工具：actionInput 缺省按空串处理，语义同文本路
            // 选择空串而非 null 是因为 ToolExecutor 内部对 null 参数的防御处理可能不一致，
            // 空串传递"我知道这个工具存在，但参数为空"的明确语义
            ToolCall action = new ToolCall(step.action(), step.actionInput() == null ? "" : step.actionInput());
            Observation observation = toolExecutor.execute(action);
            steps.add(new AgentStep(iteration, step.thought(), action.name(), action.arguments(), observation.result()));

            // 对话历史用文本回灌即可（模型读历史无需结构化、省 token）；下一步仍按 schema 产出
            conversation.append("AI:\n").append("Thought:").append(TextUtil.trimToDefault(step.thought(), "")).append("\n")
                    .append("Action:").append(action.name()).append("\n")
                    .append("Action Input:").append(action.arguments()).append("\n")
                    .append("Observation:").append(observation.toolName()).append(":")
                    .append(observation.result()).append("\n\n");
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
        return promptService.render("agent_executor.system", Map.of("toolList", toolDescriptions));
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
        return promptService.render("agent_executor_structured.system", Map.of("toolList", toolDescriptions));
    }
}
