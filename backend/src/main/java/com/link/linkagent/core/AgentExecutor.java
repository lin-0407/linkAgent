package com.link.linkagent.core;

import com.link.linkagent.api.dto.AgentChatResponse;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.tool.Tool;
import com.link.linkagent.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ReAct 主循环 —— 驱动 Thought → Action → Observation 迭代。
 * <p>
 * 核心设计：经典 ReAct 文本解析（非模型原生 Tool Calling），模型无关。
 */
@Component
public class AgentExecutor {

    private final LLMService llmService;
    private final ToolRegistry toolRegistry;

    private static final int MAX_ITERATIONS = 10;

    private static final Pattern FINAL_ANSWER = Pattern.compile(
            "Final Answer:\\s*(.*)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION = Pattern.compile(
            "Action:\\s*(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_INPUT = Pattern.compile(
            "Action Input:\\s*(.+?)(?:\\n|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern THOUGHT = Pattern.compile(
            "Thought:\\s*(.*?)(?:\\n|$)", Pattern.CASE_INSENSITIVE);

    public AgentExecutor(LLMService llmService, ToolRegistry toolRegistry) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
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
    public AgentChatResponse run(String userMessage) {
        // ---- 1. 构建系统提示词（包含工具列表） ----
        String systemPrompt = buildSystemPrompt(toolRegistry.getAllTools());

        // ---- 2. TODO(human): 初始化循环状态变量 ----
        // 你需要声明:
        //   - conversation (StringBuilder): 累积对话上下文，格式为 "Human: ...\nAI: ...\nObservation: ...\n"
        //   - steps (List<AgentStep>): 收集每一步记录
        //   - iteration (int): 当前迭代次数，初始 0
        //   - finalAnswer / stopReason: 最终结果，初始 null

        // ---- 3. TODO(human): 实现 ReAct 主循环 ----
        // while 循环中按顺序处理以下情况:
        //
        // 情况A — iteration >= MAX_ITERATIONS
        //   你选的终止策略是什么？(A/B/C 或自定义)
        //   实现它，然后 break
        //
        // 情况B — parseFinalAnswer(llmResponse) 返回非 null
        //   记录 step，break
        //
        // 情况C — parseAction(llmResponse) 返回非 null ToolCall
        //   调用 executeTool(toolCall) 得到 Observation
        //   把 Observation 拼入 conversation
        //   记录 step，iteration++
        //
        // 情况D — 既无 Final Answer 也无合法 Action
        //   LLM 返回的非结构化文本就当最终回复
        //
        // 可用方法:
        //   llmService.chat(systemPrompt, conversation.toString())
        //   parseFinalAnswer(text)   → String | null
        //   parseThought(text)       → String (可能为空)
        //   parseAction(text)        → ToolCall | null
        //   executeTool(toolCall)    → Observation

        // ---- 4. TODO(human): 返回结果 ----
        // new AgentChatResponse(finalAnswer, stopReason, steps.size(), steps)
        return null;
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
                You are a helpful assistant with access to the following tools:

                %s

                Use the following format to respond:

                Thought: your reasoning about what to do next
                Action: tool_name
                Action Input: input for the tool

                Or when you have the final answer:

                Thought: I now have the information needed
                Final Answer: your final response to the human

                Rules:
                - Only use one tool per response.
                - Always start with "Thought:" to explain your reasoning.
                - When using a tool, you MUST include BOTH "Action:" and "Action Input:".
                - When you have enough information, output "Final Answer:".
                """.formatted(toolDescriptions);
    }
}
