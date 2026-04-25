package com.link.linkagent.core;

/**
 * LLM 请求的 Tool 调用，由 ReAct 文本解析得到。
 */
public record ToolCall(
        String name,
        String arguments
) {
}
