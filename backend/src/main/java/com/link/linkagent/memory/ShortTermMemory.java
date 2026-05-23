package com.link.linkagent.memory;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 运行时使用的短期对话记忆入口。
 */
@Component
public class ShortTermMemory {

    private static final int MAX_MESSAGES_PER_SESSION = 10;

    private final ShortTermMemoryStore memoryStore;

    public ShortTermMemory(ShortTermMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    public List<MemoryMessage> getRecentMessages(String sessionId) {
        return memoryStore.getRecentMessages(sessionId);
    }

    public void append(String sessionId, String role, String content) {
        memoryStore.append(sessionId, new MemoryMessage(role, content), MAX_MESSAGES_PER_SESSION);
    }

    public List<SessionInfo> listSessions() {
        return memoryStore.listSessions();
    }

    public List<MemoryMessage> getMessages(String sessionId) {
        return memoryStore.getMessages(sessionId);
    }
}
