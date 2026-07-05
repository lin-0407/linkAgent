package com.link.linkagent.llm;

/**
 * 结构化 LLM 调用结果。
 * <p>
 * 结构化输出需要同时保留业务对象和 token 用量，是因为 Agent 执行追踪既要知道模型给出的下一步决策，
 * 也要能把该步真实消耗写入链路表，避免结构化路径成为成本统计盲区。
 *
 * @param entity 反序列化后的业务对象
 * @param promptTokens 输入 token 数，供应商未返回时为 null
 * @param completionTokens 输出 token 数，供应商未返回时为 null
 * @param totalTokens 总 token 数，供应商未返回时为 null
 * @param elapsedMs 调用耗时，单位毫秒
 * @param <T> 结构化对象类型
 */
public record StructuredCallResult<T>(
        T entity,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long elapsedMs
) {
}
