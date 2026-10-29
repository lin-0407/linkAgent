package com.link.linkagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.memory.model.ConversationMessageRecord;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 界面与推理共用同一份持久化恢复逻辑，避免只恢复可见历史却仍让模型失忆。 */
public class ConversationHistory {
    private static final Logger log = LoggerFactory.getLogger(ConversationHistory.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ConversationSessionMapper mapper;
    private final ShortTermMemory memory;

    public ConversationHistory(ConversationSessionMapper mapper, ShortTermMemory memory) {
        this.mapper = mapper;
        this.memory = memory;
    }

    public void checkOwner(String sessionId, String userId) {
        if (mapper == null) return;
        mapper.findSessionById(sessionId).ifPresent(session -> {
            if (!userId.equals(session.getUserId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
            }
        });
    }

    public Snapshot load(String sessionId, String userId) {
        List<MemoryMessage> cached = memory.getRecentMessages(sessionId);
        if (mapper == null) return new Snapshot(null, cached, false);
        try {
            checkOwner(sessionId, userId);
            ConversationMessageRecord summary = mapper.findLatestSummary(sessionId).orElse(null);
            if (summary != null && !userId.equals(summary.getUserId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
            }
            Long boundary = compressedThrough(summary);
            // 旧检查点没有原文边界，按检查点 id 截断会误删先落库的保留尾部。
            // 完整原文仍在消息表，因此恢复原文并暂不拼旧摘要，下一次压缩会写入精确边界。
            boolean legacy = summary != null && boundary == null;
            if (cached.isEmpty() || legacy) {
                List<MemoryMessage> restored = mapper.listMessagesBySession(sessionId).stream()
                        .filter(message -> userId.equals(message.getUserId()))
                        .filter(message -> !SummaryMemory.SUMMARY_MESSAGE_ROLE.equals(message.getRole()))
                        .filter(message -> boundary == null || message.getId() > boundary)
                        .map(ConversationHistory::toMemoryMessage).toList();
                memory.mergeRestoredMessages(sessionId, restored);
                // 恢复期间可能已有追加；按落库 id 合并，未落库的临时消息仍须保留。
                cached = merge(restored, memory.getRecentMessages(sessionId));
            }
            return new Snapshot(legacy ? null : summary, cached, legacy);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("读取持久会话失败，使用现有短期记忆，sessionId={}, error={}", sessionId, exception.getMessage());
            return new Snapshot(null, cached, false);
        }
    }

    public List<SessionInfo> listSessions(String userId) {
        LinkedHashMap<String, SessionInfo> sessions = new LinkedHashMap<>();
        if (mapper != null) {
            try {
                for (var session : mapper.listSessionsByUser(userId, Integer.MAX_VALUE)) {
                    Snapshot history = load(session.getSessionId(), userId);
                    List<MemoryMessage> visible = history.messages().stream()
                            .filter(message -> !message.isToolCall() && !message.isToolResult()).toList();
                    String preview = visible.isEmpty() ? session.getTitle() : visible.getLast().content();
                    sessions.put(session.getSessionId(), new SessionInfo(session.getSessionId(),
                            TextUtil.preview(preview, 48, "Empty session"), visible.size()));
                }
            } catch (Exception exception) {
                log.warn("读取持久会话列表失败，使用现有短期记忆，error={}", exception.getMessage());
            }
        }
        for (SessionInfo cached : memory.listSessions()) {
            if (sessions.containsKey(cached.sessionId())) continue;
            if (mapper != null) {
                Optional<com.link.linkagent.memory.model.ConversationSessionRecord> session;
                try {
                    session = mapper.findSessionById(cached.sessionId());
                } catch (Exception exception) {
                    continue;
                }
                if (session.isPresent() ? !userId.equals(session.get().getUserId()) : !"default".equals(userId)) continue;
            }
            sessions.put(cached.sessionId(), cached);
        }
        return List.copyOf(sessions.values());
    }

    public static Long compressedThrough(ConversationMessageRecord summary) {
        if (summary == null || summary.getToolArguments() == null) return null;
        try {
            var value = JSON.readTree(summary.getToolArguments()).path("compressedThroughMessageId");
            return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0
                    ? value.longValue() : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static MemoryMessage toMemoryMessage(ConversationMessageRecord message) {
        String role = switch (message.getRole()) {
            case "user" -> "Human";
            case "assistant" -> "AI";
            case "tool" -> ShortTermMemory.ROLE_TOOL;
            default -> message.getRole();
        };
        return new MemoryMessage(role, message.getContent(), message.getId(), message.getToolCallId(),
                message.getToolName(), message.getToolArguments());
    }

    private static List<MemoryMessage> merge(List<MemoryMessage> restored, List<MemoryMessage> cached) {
        List<MemoryMessage> merged = new ArrayList<>(restored);
        Set<Long> ids = new HashSet<>();
        restored.forEach(message -> { if (message.messageId() != null) ids.add(message.messageId()); });
        for (MemoryMessage message : cached) {
            if (message.messageId() == null || ids.add(message.messageId())) merged.add(message);
        }
        // Mapper 已按消息时间和主键排序；保留这个顺序，缓存中尚未落库的新消息追加在末尾。
        return List.copyOf(merged);
    }

    public record Snapshot(ConversationMessageRecord summary, List<MemoryMessage> messages, boolean ignoreLegacySummary) {}
}
