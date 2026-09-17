package com.link.linkagent.memory;

import com.link.linkagent.util.TextUtil;
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
 * 面向本地开发和测试的进程内短期记忆。
 * <p>
 * 不再做条数裁剪：是否压缩由请求 token 数决定，裁剪只发生在摘要压缩之后（设计文档 D1 / A1）。
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
    public void append(String sessionId, MemoryMessage message) {
        // 只追加不裁剪：10 条滑动窗口已删除，避免出现「早期对话既不进上下文、也没进摘要」的空档。
        Deque<MemoryMessage> messages = sessionMessages.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        synchronized (messages) {
            messages.addLast(message);
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
                                TextUtil.preview(latest == null ? null : latest.content(), 48, "Empty session"),
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
}
