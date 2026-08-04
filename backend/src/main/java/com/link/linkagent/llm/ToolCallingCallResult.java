package com.link.linkagent.llm;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

/**
 * 单轮原生 Function Calling 的模型响应和用量。
 * AssistantMessage 必须原样写回下一轮消息，才能让 tool_call_id 与工具结果正确配对。
 */
public record ToolCallingCallResult(
        AssistantMessage assistantMessage,
        String content,
        List<StrictToolCall> toolCalls,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long elapsedMs
) {
}
