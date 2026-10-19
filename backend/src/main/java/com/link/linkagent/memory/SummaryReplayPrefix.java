package com.link.linkagent.memory;

import com.link.linkagent.llm.ChatRequestEnvelope;

/**
 * 摘要压缩时复用「上一轮真实请求」的计划。
 *
 * <p>为什么需要这个：DeepSeek 的上下文缓存只按请求前缀命中，而供应商看到的「前缀」不只是对话文本，
 * 还包括 tools 声明、thinking 开关等请求形状。所以重放必须原样使用上一轮真实请求的信封
 * （system + 完整消息列表），只在末尾追加压缩指令。
 *
 * @param envelope 上一轮真实请求的信封；没有可复用的真实请求时为 null（走冷启动路径）
 */
public record SummaryReplayPrefix(ChatRequestEnvelope envelope) {
}
