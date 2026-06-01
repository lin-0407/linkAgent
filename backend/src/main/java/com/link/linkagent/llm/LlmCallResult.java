package com.link.linkagent.llm;

/**
 * LLM 调用结果。
 * 这里把内容和 usage 放在同一个返回对象里，是为了后续评测、成本看板和失败回放都能复用同一份调用元数据。
 */
public record LlmCallResult(
        String content,
        String modelName,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long elapsedMs
) {
}
