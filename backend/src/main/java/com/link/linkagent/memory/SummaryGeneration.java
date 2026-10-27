package com.link.linkagent.memory;

import com.link.linkagent.llm.ChatRequestEnvelope;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 一次摘要生成用到的完整调用信封，供复核复用同一前缀。
 *
 * <p>复核不再自己重新拼「系统提示词 + 原文」，而是在生成这次调用的消息列表后追加一条复核指令：
 * 生成调用刚把整段 prompt 写进供应商缓存，复核直接拿到同一前缀的命中，避免同一批 25 万 token
 * 被冷启动两次。
 *
 * <p>{@code envelope} 记录生成这次调用用的请求形状（出口、tools、thinking 等）。复核必须用同一个信封重放，
 * 否则 strict 结果函数/response_format 会改写请求形状，供应商端前缀对不上，缓存仍然不命中。
 *
 * @param summary      生成出来的摘要正文
 * @param systemPrompt 本次调用使用的系统提示词
 * @param messages     本次调用发送的消息列表（不含 system，system 在 envelope 里）
 * @param envelope     本次调用的请求信封；最小构造路径没有信封时为 null
 */
public record SummaryGeneration(String summary, String systemPrompt, List<Message> messages,
                                ChatRequestEnvelope envelope) {

    /** 兼容旧调用点：没有请求信封时走原来的独立复核链路。 */
    public SummaryGeneration(String summary, String systemPrompt, List<Message> messages) {
        this(summary, systemPrompt, messages, null);
    }
}
