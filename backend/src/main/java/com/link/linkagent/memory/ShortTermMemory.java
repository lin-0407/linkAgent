package com.link.linkagent.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local sliding window memory for early-stage multi-turn validation.
 */
@Component
public class ShortTermMemory {

    private static final int MAX_MESSAGES_PER_SESSION = 10;

    private final Map<String, Deque<MemoryMessage>> sessionMessages = new ConcurrentHashMap<>();

    public List<MemoryMessage> getRecentMessages(String sessionId) {
        Deque<MemoryMessage> messages = sessionMessages.get(sessionId);
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    public void append(String sessionId, String role, String content) {
        Deque<MemoryMessage> messages = sessionMessages.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        synchronized (messages) {
            messages.addLast(new MemoryMessage(role, content));
            while (messages.size() > MAX_MESSAGES_PER_SESSION) {
                messages.removeFirst();
            }
        }
    }
}
