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
     */
    public AgentChatResponse run(String userMessage) {
        String systemPrompt = buildSystemPrompt(toolRegistry.getAllTools());

        // ReAct 循环状态
        StringBuilder conversation = new StringBuilder();
        conversation.append("Human: ").append(userMessage).append("\n");
        List<AgentStep> steps = new ArrayList<>();
        int iteration = 0;
        String finalAnswer = null;
        String stopReason = null;

        while (true) {
            // --- 最大迭代兜底 ---
            if (iteration >= MAX_ITERATIONS) {
                // TODO(human): 达到最大迭代次数时如何终止？
                // 方案A — 返回固定错误提示
                // 方案B — 强制再调一次 LLM，要求给出 Final Answer
                // 方案C — 返回当前 conversation 中已获得的部分信息
                //
                // 请你选择并实现其中一种（或你自己想到的更优方案），
                // 然后删除这段 TODO 注释。
                break;
            }

            // 1. 调用 LLM
            String llmResponse = llmService.chat(systemPrompt, conversation.toString());
            conversation.append("AI: ").append(llmResponse).append("\n");

            // 2. 解析 LLM 响应
            String answer = parseFinalAnswer(llmResponse);
            String thought = parseThought(llmResponse);

            if (answer != null) {
                finalAnswer = answer;
                stopReason = "final_answer_found";
                steps.add(new AgentStep(iteration, thought, null, null, null));
                break;
            }

            ToolCall toolCall = parseAction(llmResponse);
            if (toolCall != null) {
                // 3. 执行工具
                Observation observation = executeTool(toolCall);
                conversation.append("Observation: ")
                        .append(observation.toolName()).append(" → ")
                        .append(observation.result()).append("\n");

                steps.add(new AgentStep(iteration, thought,
                        toolCall.name(), toolCall.arguments(),
                        observation.result()));
                iteration++;
                continue;
            }

            // 既无 Final Answer 也无合法 Action → 整段视为最终回复
            finalAnswer = llmResponse;
            stopReason = "unparseable_response";
            steps.add(new AgentStep(iteration, thought, null, null, null));
            break;
        }

        return new AgentChatResponse(finalAnswer, stopReason, steps.size(), steps);
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
