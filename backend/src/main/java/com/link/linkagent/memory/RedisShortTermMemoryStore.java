package com.link.linkagent.memory;

import com.link.linkagent.util.TextUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 面向多实例部署的 Redis 短期记忆。
 * <p>
 * 不再做 LTRIM 裁剪：按条数保留会让「早期对话」永远进不了上下文，
 * 现在只在 token 超过压缩阈值时由上层生成摘要并裁剪（设计文档 D1 / A1）。
 */
@Component
@ConditionalOnProperty(prefix = "agent.memory.short-term", name = "store-type", havingValue = "redis")
public class RedisShortTermMemoryStore implements ShortTermMemoryStore {

    private static final String KEY_PREFIX = "link-agent:memory:short-term:";
    private static final String FIELD_SEPARATOR = "\t";

    /**
     * 「删旧 + 写新」的原子替换脚本（F3）。
     * <p>
     * <b>为什么必须是原子的</b>：原来分成 {@code DEL} 与 {@code RPUSH} 两条命令，两个命令之间有一个窗口：
     * 并发读（同会话另一轮对话正在组装上下文）会看到空列表，那一轮就凭空少了「最近说了什么」；
     * 并发追加会插在保留消息前面，顺序错乱；进程正好在这中间挂掉，整个会话的短期记忆就没了。
     * <p>
     * <b>为什么用 Lua 而不是 MULTI/EXEC</b>：MULTI/EXEC 要拿底层连接、自己处理序列化，
     * 而这段逻辑用一条脚本就能表达清楚，且只占一次往返。脚本内的循环而不是 {@code unpack(ARGV)}，
     * 是为了绕开 Lua 参数栈上限（消息条数虽然少，但没有理由留这个坑）。
     * <p>
     * Redis 执行脚本时不会插入其它命令，所以读者只会看到替换前或替换后的完整列表。
     */
    private static final RedisScript<Long> REPLACE_MESSAGES_SCRIPT = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            for i = 1, #ARGV do
              redis.call('RPUSH', KEYS[1], ARGV[i])
            end
            return #ARGV
            """, Long.class);

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
    public void append(String sessionId, MemoryMessage message) {
        // 只追加不裁剪：裁剪时机由摘要压缩决定，保留最近 N 条的规则已删除。
        redisTemplate.opsForList().rightPush(buildKey(sessionId), serialize(message));
    }

    @Override
    public void replaceMessages(String sessionId, List<MemoryMessage> messages) {
        String key = buildKey(sessionId);
        // 序列化在 Java 侧先做完：脚本里只有一次 DEL 与 N 次 RPUSH，中间不会被别的命令插进来（F3）
        Object[] values = messages.stream()
                .map(this::serialize)
                .toArray();
        redisTemplate.execute(REPLACE_MESSAGES_SCRIPT, List.of(key), values);
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
        return new SessionInfo(sessionId, TextUtil.preview(latest.content(), 48, "Empty session"), messages.size());
    }

    /**
     * 序列化格式：{@code 消息id \t 角色 \t 正文}。
     * <p>
     * 第一列留给消息表主键，是长期记忆游标判断「这条消息走过没有」的依据（设计文档 A6 / C4）；
     * 没有落库的消息写空串。角色与正文里的制表符会被 escape，不会和分隔符混淆。
     */
    private String serialize(MemoryMessage message) {
        String messageId = message.messageId() == null ? "" : message.messageId().toString();
        return messageId + FIELD_SEPARATOR + escape(message.role()) + FIELD_SEPARATOR + escape(message.content());
    }

    /**
     * 反序列化，同时兼容没有消息 id 的旧数据。
     * <p>
     * 判断方式：第一列是数字或空串说明是带 id 的新格式；否则第一列就是角色名，按旧格式解析。
     * 兼容旧数据是必要的——Redis 列表里的历史消息不会因为这次改造被重写，读不出来等于丢历史。
     */
    private MemoryMessage deserialize(String value) {
        int firstSeparator = value.indexOf(FIELD_SEPARATOR);
        if (firstSeparator < 0) {
            return null;
        }
        String firstField = value.substring(0, firstSeparator);
        if (isMessageIdField(firstField)) {
            int secondSeparator = value.indexOf(FIELD_SEPARATOR, firstSeparator + FIELD_SEPARATOR.length());
            if (secondSeparator < 0) {
                return null;
            }
            Long messageId = parseMessageId(firstField);
            String role = unescape(value.substring(firstSeparator + FIELD_SEPARATOR.length(), secondSeparator));
            String content = unescape(value.substring(secondSeparator + FIELD_SEPARATOR.length()));
            return new MemoryMessage(role, content, messageId);
        }
        String role = unescape(firstField);
        String content = unescape(value.substring(firstSeparator + FIELD_SEPARATOR.length()));
        return new MemoryMessage(role, content);
    }

    /** 解析消息 id 列；空串或超出 long 范围时返回 null，不让一条脏数据打断整段上下文组装。 */
    private Long parseMessageId(String field) {
        if (field.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(field);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 消息 id 列只可能是空串或纯数字；角色名不会是这个形状，因此可以用来区分新旧格式。 */
    private boolean isMessageIdField(String field) {
        if (field.isEmpty()) {
            return true;
        }
        for (int i = 0; i < field.length(); i++) {
            if (!Character.isDigit(field.charAt(i))) {
                return false;
            }
        }
        return true;
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
