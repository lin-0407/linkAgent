package com.link.linkagent.memory;

import java.util.List;

/**
 * 短期对话记忆的存储边界。
 */
public interface ShortTermMemoryStore {

    List<MemoryMessage> getRecentMessages(String sessionId);

    /**
     * 追加一条消息。
     * <p>
     * 从 2026-09 的记忆重构起，这里不再接收条数上限：短期记忆不再按条数裁剪，
     * 是否压缩由请求 token 数决定（见设计文档 D1 / A1）。裁剪只发生在摘要压缩之后，
     * 走 {@link #replaceMessages}。
     */
    void append(String sessionId, MemoryMessage message);

    void replaceMessages(String sessionId, List<MemoryMessage> messages);

    List<SessionInfo> listSessions();

    List<MemoryMessage> getMessages(String sessionId);
}
