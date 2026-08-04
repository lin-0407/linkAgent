package com.link.linkagent.llm;

/**
 * 已从原生 tool_calls 参数中解析并通过工具名白名单校验的调用。
 */
public record StrictToolCall(
        String id,
        String functionName,
        String toolName,
        String input
) {
}
