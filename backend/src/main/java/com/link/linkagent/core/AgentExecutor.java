package com.link.linkagent.core;

import ch.qos.logback.core.util.StringUtil;
import com.link.linkagent.api.dto.AgentChatResponse;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.memory.MemoryMessage;
import com.link.linkagent.memory.ShortTermMemory;
import com.link.linkagent.memory.SummaryMemory;
import com.link.linkagent.tool.Tool;
import com.link.linkagent.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReAct 主循环 —— 驱动 Thought → Action → Observation 迭代。
 * <p>
 * 核心设计：经典 ReAct 文本解析（非模型原生 Tool Calling），模型无关。
 */
@Component
public class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final ShortTermMemory shortTermMemory;
    private final SummaryMemory summaryMemory;

    private static final int MAX_ITERATIONS = 10;

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

    public AgentExecutor(LLMService llmService, ToolRegistry toolRegistry, ShortTermMemory shortTermMemory,
                         SummaryMemory summaryMemory) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.shortTermMemory = shortTermMemory;
        this.summaryMemory = summaryMemory;
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
        String resolvedSessionId = resolveSessionId(sessionId);
        String systemPrompt = buildSystemPrompt(toolRegistry.getAllTools());

        StringBuilder conversation = new StringBuilder();
        String finalAnswer = null;
        int iteration = 0;
        List<AgentStep> steps = new ArrayList<>();

        // 拼接用户输入作为对话起点，格式与系统提示词约定一致
        appendSummary(conversation, summaryMemory.getSummary(resolvedSessionId));
        List<MemoryMessage> recentMessages = shortTermMemory.getRecentMessages(resolvedSessionId);
        appendMemory(conversation, recentMessages);
        conversation.append("Human:").append(userMessage).append("\n\n");
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
            if(!StringUtil.isNullOrEmpty(finalAnswer)){
                log.info("第{}轮解析结果: thought={}, finalAnswer={}", iteration, parseThought(llmAnswer), finalAnswer);
                shortTermMemory.append(resolvedSessionId, "Human", userMessage);
                shortTermMemory.append(resolvedSessionId, "AI", finalAnswer);
                if (summaryMemory.shouldSummarize(resolvedSessionId, shortTermMemory.getRecentMessages(resolvedSessionId))) {
                    shortTermMemory.keepRecentMessages(resolvedSessionId, summaryMemory.getRetainedMessageCount());
                    log.info("摘要记忆已达到触发条件，sessionId={}", resolvedSessionId);
                }
                return new AgentChatResponse(resolvedSessionId, finalAnswer, null, steps.size(), steps);
            }

            // 3. 提取 Thought — 必须存在，否则说明LLM未按格式输出
            String thought = parseThought(llmAnswer);
            if(StringUtil.isNullOrEmpty(thought)){
                // LLM 既未给出 Final Answer 也未给出合法 Thought，反馈错误让 LLM 重试
                log.warn("第{}轮未解析到合法 Thought，rawResponse={}", iteration, llmAnswer);
                steps.add(new AgentStep(iteration, null, null, null, null));
                conversation.append("ERROR：输出格式错误，请严格按照格式输出！\n\n");
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
            Observation observation = executeTool(action);
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

    private String resolveSessionId(String sessionId) {
        if (StringUtil.isNullOrEmpty(sessionId) || StringUtil.isNullOrEmpty(sessionId.trim())) {
            return UUID.randomUUID().toString();
        }
        return sessionId.trim();
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

    private void appendSummary(StringBuilder conversation, String summary) {
        if (StringUtil.isNullOrEmpty(summary) || StringUtil.isNullOrEmpty(summary.trim())) {
            return;
        }
        conversation.append("Conversation summary:\n")
                .append(summary.trim())
                .append("\n\n");
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

    private Observation executeTool(ToolCall toolCall) {
        Tool tool = toolRegistry.getTool(toolCall.name());
        if (tool == null) {
            return new Observation(toolCall.name(), "Error: tool '" + toolCall.name() + "' not found");
        }
        try {
            String result = tool.execute(toolCall.arguments());
            return new Observation(toolCall.name(), result);
        } catch (Exception e) {
            return new Observation(toolCall.name(), "Error: " + e.getMessage());
        }
    }

    private String buildSystemPrompt(Collection<Tool> tools) {
        String toolDescriptions = tools.stream()
                .map(t -> "- " + t.getName() + ": " + t.getDescription())
                .collect(Collectors.joining("\n"));

        return """
            你是LinkAgent，可以使用以下工具:
        
        %s
        
            请使用以下格式回复:
        
            Thought:你对接下来要做什么的推理
            Action:工具名称
            Action Input:工具的输入内容
        
            或者当你已经获得最终答案时:
        
            Thought:我现在已经掌握了所需信息
            Final Answer:你对Human的最终回复
        
            规则:
            - 每次只使用一个工具。
            - 始终以"Thought:"开头来解释你的推理。
            - 使用工具时，必须同时包含"Action:"和"Action Input:"。
            - 当你掌握了足够的信息，就输出"Final Answer:"。
        """.formatted(toolDescriptions);
    }
}
