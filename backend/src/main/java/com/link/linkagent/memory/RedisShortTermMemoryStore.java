package com.link.linkagent.memory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 面向多实例部署的 Redis 滑动窗口记忆。
 */
@Component
@ConditionalOnProperty(prefix = "agent.memory.short-term", name = "store-type", havingValue = "redis")
public class RedisShortTermMemoryStore implements ShortTermMemoryStore {

    private static final String KEY_PREFIX = "link-agent:memory:short-term:";
    private static final String FIELD_SEPARATOR = "\t";

    private final StringRedisTemplate redisTemplate;

    public RedisShortTermMemoryStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public List<MemoryMessage> getRecentMessages(String sessionId) {
        List<String> values = redisTemplate.opsForList().range(buildKey(sessionId), 0, -1);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(this::deserialize)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public void append(String sessionId, MemoryMessage message, int maxMessages) {
        ListOperations<String, String> operations = redisTemplate.opsForList();
        String key = buildKey(sessionId);
        operations.rightPush(key, serialize(message));
        operations.trim(key, -maxMessages, -1);
    }

    @Override
    public void replaceMessages(String sessionId, List<MemoryMessage> messages) {
        String key = buildKey(sessionId);
        redisTemplate.delete(key);
        if (messages.isEmpty()) {
            return;
        }
        List<String> values = messages.stream()
                .map(this::serialize)
                .toList();
        redisTemplate.opsForList().rightPushAll(key, values);
    }

    @Override
    public List<SessionInfo> listSessions() {
        List<String> keys = scanKeys();
        if (keys.isEmpty()) {
            return List.of();
        }
        return keys.stream()
                .map(this::toSessionInfo)
                .filter(Objects::nonNull)
                .sorted((left, right) -> Long.compare(right.messageCount(), left.messageCount()))
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryMessage> getMessages(String sessionId) {
        List<String> values = redisTemplate.opsForList().range(buildKey(sessionId), 0, -1);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(this::deserialize)
                .filter(Objects::nonNull)
                .toList();
    }

    private String buildKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private List<String> scanKeys() {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(KEY_PREFIX + "*")
                .count(100)
                .build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }

    private SessionInfo toSessionInfo(String key) {
        List<String> values = redisTemplate.opsForList().range(key, 0, -1);
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<MemoryMessage> messages = values.stream()
                .map(this::deserialize)
                .filter(Objects::nonNull)
                .toList();
        if (messages.isEmpty()) {
            return null;
        }
        String sessionId = key.substring(KEY_PREFIX.length());
        MemoryMessage latest = messages.get(messages.size() - 1);
        return new SessionInfo(sessionId, buildPreview(latest), messages.size());
    }

    private String buildPreview(MemoryMessage latest) {
        if (latest == null || latest.content() == null || latest.content().isBlank()) {
            return "Empty session";
        }
        String content = latest.content().replaceAll("\\s+", " ").trim();
        return content.length() <= 48 ? content : content.substring(0, 48) + "...";
    }

    private String serialize(MemoryMessage message) {
        return escape(message.role()) + FIELD_SEPARATOR + escape(message.content());
    }

    private MemoryMessage deserialize(String value) {
        int separatorIndex = value.indexOf(FIELD_SEPARATOR);
        if (separatorIndex < 0) {
            return null;
        }
        String role = unescape(value.substring(0, separatorIndex));
        String content = unescape(value.substring(separatorIndex + FIELD_SEPARATOR.length()));
        return new MemoryMessage(role, content);
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private String unescape(String value) {
        StringBuilder result = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!escaping) {
                if (ch == '\\') {
                    escaping = true;
                } else {
                    result.append(ch);
                }
                continue;
            }
            result.append(switch (ch) {
                case 't' -> '\t';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case '\\' -> '\\';
                default -> ch;
            });
            escaping = false;
        }
        if (escaping) {
            result.append('\\');
        }
        return result.toString();
    }
}
