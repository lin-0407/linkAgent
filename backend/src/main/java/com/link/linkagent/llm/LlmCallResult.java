package com.link.linkagent.llm;

/**
 * LLM 调用结果。
 * 这里把内容和 usage 放在同一个返回对象里，是为了后续评测、成本看板和失败回放都能复用同一份调用元数据。
 *
 * <p>{@code cachedPromptTokens} 是供应商返回的「这次请求命中了前缀缓存」的输入 token 数
 * （DeepSeek 的 {@code prompt_tokens_details.cached_tokens}）。单独记它才能算命中率：
 * 命中率和 prompt_tokens 是两件事，前者直接对应成本，后者只对应体积。
 *
 * <p>{@code finishReason} 是供应商的结束原因（{@code stop} / {@code length} / {@code tool_calls} 等）。
 * 摘要生成必须看它：{@code length} 表示输出被 max_tokens 截断，这时候的“摘要”是不完整的，
 * 不能拿去复核或落库，否则会进入「截断 → 复核判漏抽 → 再生成 → 再截断」的死循环。
 */
public record LlmCallResult(
        String content,
        String modelName,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer cachedPromptTokens,
        String finishReason,
        Long elapsedMs
) {

    /** 兼容旧调用点：没有缓存命中与结束原因信息时传 null，避免为了统计字段改所有构造处。 */
    public LlmCallResult(String content, String modelName, Integer promptTokens, Integer completionTokens,
                         Integer totalTokens, Long elapsedMs) {
        this(content, modelName, promptTokens, completionTokens, totalTokens, null, null, elapsedMs);
    }

    /** 兼容只有缓存字段的调用点。 */
    public LlmCallResult(String content, String modelName, Integer promptTokens, Integer completionTokens,
                         Integer totalTokens, Integer cachedPromptTokens, Long elapsedMs) {
        this(content, modelName, promptTokens, completionTokens, totalTokens, cachedPromptTokens, null, elapsedMs);
    }

    /** 是否被输出上限截断：不同供应商可能叫 length 或 max_tokens。 */
    public boolean truncatedByOutputLimit() {
        return "length".equalsIgnoreCase(finishReason) || "max_tokens".equalsIgnoreCase(finishReason);
    }
}
