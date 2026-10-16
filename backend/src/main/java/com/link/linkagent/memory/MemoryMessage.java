package com.link.linkagent.memory;

/**
 * 短期对话记忆中的单条消息。
 *
 * @param role      消息角色，如 "Human" / "AI"；从消息表回读时是 "user" / "assistant"
 * @param content   消息正文
 * @param messageId 这条消息在 t_conversation_message 里的主键；为 null 表示没有落库（写库失败或旧数据）。
 *                  长期记忆游标记的是消息 id，短期记忆带上它才能判断「这条消息游标走过没有」（设计文档 A6 / C4）。
 */
public record MemoryMessage(
        String role,
        String content,
        Long messageId
) {

    /** 不带消息 id 的构造：摘要、复核这些只关心内容的链路用它，避免调用方为了编译补一堆 null。 */
    public MemoryMessage(String role, String content) {
        this(role, content, null);
    }
}
