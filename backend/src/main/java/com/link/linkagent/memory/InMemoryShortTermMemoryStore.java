package com.link.linkagent.memory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面向本地开发和测试的进程内滑动窗口记忆。
 */
@Component
@ConditionalOnProperty(prefix = "agent.memory.short-term", name = "store-type", havingValue = "memory", matchIfMissing = true)
public class InMemoryShortTermMemoryStore implements ShortTermMemoryStore {

    private final Map<String, Deque<MemoryMessage>> sessionMessages = new ConcurrentHashMap<>();

    @Override
    public List<MemoryMessage> getRecentMessages(String sessionId) {
        Deque<MemoryMessage> messages = sessionMessages.get(sessionId);
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    @Override
    public void append(String sessionId, MemoryMessage message, int maxMessages) {
        Deque<MemoryMessage> messages = sessionMessages.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        synchronized (messages) {
            messages.addLast(message);
            while (messages.size() > maxMessages) {
                messages.removeFirst();
            }
        }
    }

    @Override
    public void replaceMessages(String sessionId, List<MemoryMessage> messages) {
        Deque<MemoryMessage> newMessages = new ArrayDeque<>(messages);
        sessionMessages.put(sessionId, newMessages);
    }

    @Override
    public List<SessionInfo> listSessions() {
        return sessionMessages.entrySet().stream()
                .map(entry -> {
                    Deque<MemoryMessage> messages = entry.getValue();
                    synchronized (messages) {
                        MemoryMessage latest = messages.peekLast();
                        return new SessionInfo(
                                entry.getKey(),
                                buildPreview(latest),
                                messages.size()
                        );
                    }
                })
                .sorted(Comparator.comparingLong(SessionInfo::messageCount).reversed())
                .toList();
    }

    @Override
    public List<MemoryMessage> getMessages(String sessionId) {
        return getRecentMessages(sessionId);
    }

    private String buildPreview(MemoryMessage latest) {
        if (latest == null || latest.content() == null || latest.content().isBlank()) {
            return "Empty session";
        }
        String content = latest.content().replaceAll("\\s+", " ").trim();
        return content.length() <= 48 ? content : content.substring(0, 48) + "...";
    }
}
