package com.link.linkagent.memory;

import com.link.linkagent.util.TextUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
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
        Deque<MemoryMessage> current = sessionMessages.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        // 保留同一个锁对象，避免恢复或追加持有被替换的旧 Deque，写入后却不再可见。
        synchronized (current) {
            current.clear();
            current.addAll(messages);
        }
    }

    @Override
    public void mergeRestoredMessages(String sessionId, List<MemoryMessage> messages) {
        Deque<MemoryMessage> current = sessionMessages.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        synchronized (current) {
            List<MemoryMessage> merged = new ArrayList<>();
            var ids = new HashSet<Long>();
            for (MemoryMessage message : messages) {
                if (message.messageId() == null || ids.add(message.messageId())) merged.add(message);
            }
            for (MemoryMessage message : current) {
                if (message.messageId() == null || ids.add(message.messageId())) merged.add(message);
            }
            current.clear();
            current.addAll(merged);
        }
    }

    @Override
    public void removeSnapshotMessages(String sessionId, List<MemoryMessage> messages) {
        Deque<MemoryMessage> current = sessionMessages.get(sessionId);
        if (current == null || messages.isEmpty()) return;
        var ids = new HashSet<Long>();
        var anonymous = new HashMap<MemoryMessage, Integer>();
        for (MemoryMessage message : messages) {
            if (message.messageId() != null) ids.add(message.messageId());
            else anonymous.merge(message, 1, Integer::sum);
        }
        synchronized (current) {
            current.removeIf(message -> {
                if (message.messageId() != null) return ids.contains(message.messageId());
                int count = anonymous.getOrDefault(message, 0);
                if (count == 0) return false;
                // 相同的未落库消息可能又追加了一次，只删除快照中的次数。
                anonymous.put(message, count - 1);
                return true;
            });
        }
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
                                // 工具调用消息正文为空，预览用渲染后的 ToolCall 文本。
                                TextUtil.preview(latest == null ? null : latest.promptText(), 48, "Empty session"),
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
