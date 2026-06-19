package com.link.linkagent.core;

import com.link.linkagent.dto.AgentChatResponse;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.memory.LongTermMemory;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
    /** 生产环境从运行期设置读取结构化开关；单测不注入设置服务时回退到构造器默认值。 */
    private final boolean structuredKernelDefaultEnabled;

    private static final int MAX_ITERATIONS = 10;

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
                         RuntimeSettingService runtimeSettingService) {
        this(llmService, toolRegistry, toolExecutor, shortTermMemory, summaryMemory, longTermMemory,
                longTermMemoryExtractor, promptService, runtimeSettingService, true);
    }

    public AgentExecutor(LLMService llmService, ToolRegistry toolRegistry, ToolExecutor toolExecutor,
                         ShortTermMemory shortTermMemory,
                         SummaryMemory summaryMemory, LongTermMemory longTermMemory,
                         LongTermMemoryExtractor longTermMemoryExtractor,
                         PromptService promptService) {
        this(llmService, toolRegistry, toolExecutor, shortTermMemory, summaryMemory, longTermMemory,
                longTermMemoryExtractor, promptService, null, false);
    }

    AgentExecutor(LLMService llmService, ToolRegistry toolRegistry, ToolExecutor toolExecutor,
                  ShortTermMemory shortTermMemory,
                  SummaryMemory summaryMemory, LongTermMemory longTermMemory,
                  LongTermMemoryExtractor longTermMemoryExtractor,
                  PromptService promptService,
                  boolean structuredKernelDefaultEnabled) {
        this(llmService, toolRegistry, toolExecutor, shortTermMemory, summaryMemory, longTermMemory,
                longTermMemoryExtractor, promptService, null, structuredKernelDefaultEnabled);
    }

    private AgentExecutor(LLMService llmService, ToolRegistry toolRegistry, ToolExecutor toolExecutor,
                          ShortTermMemory shortTermMemory,
                          SummaryMemory summaryMemory, LongTermMemory longTermMemory,
                          LongTermMemoryExtractor longTermMemoryExtractor,
                          PromptService promptService,
                          RuntimeSettingService runtimeSettingService,
                          boolean structuredKernelDefaultEnabled) {
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
    }

    /**
     * 执行 ReAct 循环，返回最终答案及完整步骤追踪。
     * <p>
     * ReAct 算法核心流程：
     * <pre>
     * while (未终止) {
     *     1. 检查迭代上限 → 兜底终止
     *     2. LLM 思考 → 生成 Thought (+ Action + Action Input)
     *     3. 解析到 "Final Answer" → 成功终止
     *     4. 解析到 "Action" → 执行工具 → 拼接 Observation → 回到步骤 2
     *     5. 解析不到任何结构化输出 → 兜底终止
     * }
     * </pre>
     *
     * @param userMessage 用户原始输入
     * @return 最终答案 + 步骤追踪
     */
    public AgentChatResponse run(String sessionId, String userMessage) {
        return run(sessionId, "default", userMessage);
    }

    public AgentChatResponse run(String sessionId, String userId, String userMessage) {
        String resolvedSessionId = resolveSessionId(sessionId);
        String resolvedUserId = resolveUserId(userId);

        // 拼接记忆 + 用户输入作为对话起点，格式与系统提示词约定一致
        StringBuilder conversation = new StringBuilder();
        appendLongTermMemory(conversation, longTermMemory.listByUser(resolvedUserId, 10));
        appendSummary(conversation, summaryMemory.getSummary(resolvedSessionId));
        List<MemoryMessage> recentMessages = shortTermMemory.getRecentMessages(resolvedSessionId);
        appendMemory(conversation, recentMessages);
        conversation.append("Human:").append(userMessage).append("\n\n");

        // 结构化内核（5.4+）：开关开则每步走受 schema 约束的 ReActStep；关闭时回退文本 ReAct 兜底。
        if (isStructuredKernelEnabled()) {
            String structuredSystemPrompt = buildStructuredSystemPrompt(toolRegistry.getAllTools());
            List<AgentStep> structuredSteps = new ArrayList<>();
            String structuredAnswer = runStructuredLoop(structuredSystemPrompt, conversation, structuredSteps);
            if (structuredAnswer == null) {
                return new AgentChatResponse(resolvedSessionId, null, "迭代次数超过上限", structuredSteps.size(), structuredSteps);
            }
            persistChatTurn(resolvedSessionId, resolvedUserId, userMessage, structuredAnswer);
            return new AgentChatResponse(resolvedSessionId, structuredAnswer, null, structuredSteps.size(), structuredSteps);
        }

        // 文本路（原 ReAct 文本解析循环，逻辑保持不变）
        String systemPrompt = buildSystemPrompt(toolRegistry.getAllTools());
        String finalAnswer = null;
        int iteration = 0;
        List<AgentStep> steps = new ArrayList<>();
        while(true){
            iteration++;

            // 迭代上限兜底：防止无限循环耗尽资源
            if(iteration > MAX_ITERATIONS){
                return new AgentChatResponse(resolvedSessionId, finalAnswer, "迭代次数超过上限", steps.size(), steps);
            }

            log.info("正在进行第{}轮ReAct迭代...", iteration);

            // 1. 调用LLM，传入完整对话历史
            String llmAnswer = llmService.chat(systemPrompt, conversation.toString());
            log.info("第{}轮LLM原始响应:\n{}", iteration, llmAnswer);

            // 2. 优先检测 Final Answer（即使同时存在 Action 也以 Final Answer 为准）
            finalAnswer = parseFinalAnswer(llmAnswer);
            if (TextUtil.hasText(finalAnswer)) {
                log.info("第{}轮解析结果: thought={}, finalAnswer={}", iteration, parseThought(llmAnswer), finalAnswer);
                persistChatTurn(resolvedSessionId, resolvedUserId, userMessage, finalAnswer);
                return new AgentChatResponse(resolvedSessionId, finalAnswer, null, steps.size(), steps);
            }

            // 3. 提取 Thought — 必须存在，否则说明LLM未按格式输出
            String thought = parseThought(llmAnswer);
            if (TextUtil.isBlank(thought)) {
                // LLM 既未给出 Final Answer 也未给出合法 Thought，反馈错误让 LLM 重试
                log.warn("第{}轮未解析到合法 Thought，rawResponse={}", iteration, llmAnswer);
                steps.add(new AgentStep(iteration, "未解析到 Thought，模型可能没有按提示词要求输出。", null, null,
                        FORMAT_ERROR_OBSERVATION));
                conversation.append("ERROR：输出格式错误，请严格按照格式输出！\n\n");
                continue;
            }
            log.info("第{}轮解析到 Thought: {}", iteration, thought);

            // 4. 尝试解析 Action + Action Input
            ToolCall action = parseAction(llmAnswer);
            if(action == null){
                // LLM 既未给出 Final Answer 也未给出合法 Action，反馈错误让 LLM 重试
                log.warn("第{}轮未解析到合法 Action，rawResponse={}", iteration, llmAnswer);
                steps.add(new AgentStep(iteration, thought, null, null, null));
                conversation.append("ERROR：输出格式错误，请严格按照格式输出！\n\n");
                continue;
            }
            log.info("第{}轮解析到 Action: {}, Action Input: {}", iteration, action.name(), action.arguments());

            // 5. 执行工具，得到 Observation
            Observation observation = toolExecutor.execute(action);
            log.info("第{}轮工具执行结果: tool={}, observation={}", iteration, observation.toolName(), observation.result());
            steps.add(new AgentStep(iteration, thought, action.name(), action.arguments(), observation.result()));

            // 6. 将本轮的 Thought/Action/Observation 拼回对话，供下一轮 LLM 参考
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
        // 结构化内核（5.4+）：任务推理同样优先走结构化每步；关闭时走下方文本路兜底。
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

        // 工具集与系统提示词复用 run 的同一套：RAG 开启时 getAllTools() 自带 knowledge_search
        String systemPrompt = buildSystemPrompt(toolRegistry.getAllTools());
        StringBuilder conversation = new StringBuilder();
        // 不加载任何记忆，仅以任务说明作为对话起点
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

            // 优先检测 Final Answer：拿到自由文本结论即终止，且全程不写任何记忆
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

    private String resolveSessionId(String sessionId) {
        if (TextUtil.isBlank(sessionId)) {
            return UUID.randomUUID().toString();
        }
        return sessionId.trim();
    }

    private String resolveUserId(String userId) {
        if (TextUtil.isBlank(userId)) {
            return "default";
        }
        return userId.trim();
    }

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

    private void tryExtractAndSaveLongTermMemory(String userId, String sessionId, String userMessage, String finalAnswer) {
        longTermMemoryExtractor.extract(userMessage, finalAnswer)
                .ifPresent(candidate -> saveLongTermMemory(userId, sessionId, candidate));
    }

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
        if (summaryMemory.shouldSummarize(sessionId, shortTermMemory.getRecentMessages(sessionId))) {
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

            // 执行工具（actionInput 缺省按空串，语义同文本路）
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

    private void appendSummary(StringBuilder conversation, String summary) {
        if (TextUtil.isBlank(summary)) {
            return;
        }
        conversation.append("Conversation summary:\n")
                .append(summary.trim())
                .append("\n\n");
    }

    private boolean isStructuredKernelEnabled() {
        // 生产环境走运行期设置，方便结构化输出异常时快速切回文本兜底；单测构造器不强迫注入设置模块。
        return runtimeSettingService == null
                ? structuredKernelDefaultEnabled
                : runtimeSettingService.isStructuredKernelEnabled();
    }

    /**
     * 从 LLM 响应中提取 Final Answer，不存在则返回 null。
     */
    String parseFinalAnswer(String text) {
        Matcher m = FINAL_ANSWER.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * 从 LLM 响应中提取 Action + Action Input，两者必须同时存在才返回有效 ToolCall。
     */
    ToolCall parseAction(String text) {
        Matcher actionMatcher = ACTION.matcher(text);
        Matcher inputMatcher = ACTION_INPUT.matcher(text);
        if (actionMatcher.find() && inputMatcher.find()) {
            return new ToolCall(actionMatcher.group(1).trim(), inputMatcher.group(1).trim());
        }
        return null;
    }

    private String parseThought(String text) {
        Matcher m = THOUGHT.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private String buildSystemPrompt(Collection<Tool> tools) {
        String toolDescriptions = tools.stream()
                .map(t -> "- " + t.getName() + ": " + t.getDescription())
                .collect(Collectors.joining("\n"));
        return promptService.render("agent_executor.system", Map.of("toolList", toolDescriptions));
    }

    /**
     * 结构化内核的系统提示词（阶段 5.4）：列出工具 + 要求「每步要么调用工具(action+actionInput)、要么给 finalAnswer」。
     * 不再写 Thought:/Action: 文本格式约定——具体 JSON schema 由 chatStructured 的 BeanOutputConverter 自动追加进 prompt。
     */
    private String buildStructuredSystemPrompt(Collection<Tool> tools) {
        String toolDescriptions = tools.stream()
                .map(t -> "- " + t.getName() + ": " + t.getDescription())
                .collect(Collectors.joining("\n"));
        return promptService.render("agent_executor_structured.system", Map.of("toolList", toolDescriptions));
    }
}
