package com.link.linkagent.memory;

import java.util.List;

/**
 * 短期对话记忆的存储边界。
 */
public interface ShortTermMemoryStore {

    List<MemoryMessage> getRecentMessages(String sessionId);

    void append(String sessionId, MemoryMessage message, int maxMessages);

    void replaceMessages(String sessionId, List<MemoryMessage> messages);

    List<SessionInfo> listSessions();

    List<MemoryMessage> getMessages(String sessionId);
}
