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
    /** 序列化格式版本标记：第三列写 v2 表示带工具调用配对字段的新格式；旧数据没有这一列。 */
    private static final String FORMAT_VERSION = "v2";
    /** null 字段占位：反斜杠会被 escape 成两个，因此 \\N 不会与正文内容冲突。 */
    private static final String NULL_FIELD = "\\N";

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
        // 工具调用消息正文为空，预览要用渲染后的 ToolCall 文本，否则会话列表显示「Empty session」。
        return new SessionInfo(sessionId, TextUtil.preview(latest.promptText(), 48, "Empty session"), messages.size());
    }

    /**
     * 序列化格式（v2）：{@code 消息id \t v2 \t 角色 \t toolCallId \t toolName \t toolArguments \t 正文}。
     * <p>
     * 第一列留给消息表主键，是长期记忆游标判断「这条消息走过没有」的依据（设计文档 A6 / C4）；
     * 没有落库的消息写空串。第三列固定写 {@code v2} 用来和旧的三列格式区分
     * （正文里的制表符/换行都 escape 过，不会和分隔符混淆）。
     * Tool 调用与结果必须带 toolCallId/toolName/toolArguments，否则重启后从 Redis 读回来只剩 role/content，
     * 渲染和裁剪配对判断都会失效。
     */
    private String serialize(MemoryMessage message) {
        String messageId = message.messageId() == null ? "" : message.messageId().toString();
        return String.join(FIELD_SEPARATOR,
                messageId,
                FORMAT_VERSION,
                escape(message.role()),
                encodeNullable(message.toolCallId()),
                encodeNullable(message.toolName()),
                encodeNullable(message.toolArguments()),
                escape(message.content()));
    }

    /**
     * 反序列化，兼容三种历史格式：
     * <ul>
     *   <li>v2 新格式（7 列，第三列为 v2）：带工具调用配对字段；</li>
     *   <li>带消息 id 的旧格式（3 列：id / 角色 / 正文）；</li>
     *   <li>最早的旧格式（2 列：角色 / 正文）。</li>
     * </ul>
     * 兼容旧数据是必要的——Redis 列表里的历史消息不会因为这次改造被重写，读不出来等于丢历史。
     */
    private MemoryMessage deserialize(String value) {
        String[] fields = value.split(FIELD_SEPARATOR, -1);
        if (fields.length == 7 && FORMAT_VERSION.equals(fields[1])) {
            return new MemoryMessage(
                    unescape(fields[2]),
                    unescape(fields[6]),
                    parseMessageId(fields[0]),
                    decodeNullable(fields[3]),
                    decodeNullable(fields[4]),
                    decodeNullable(fields[5]));
        }
        if (fields.length == 3) {
            return new MemoryMessage(unescape(fields[1]), unescape(fields[2]), parseMessageId(fields[0]));
        }
        if (fields.length == 2) {
            return new MemoryMessage(unescape(fields[0]), unescape(fields[1]));
        }
        return null;
    }

    private String encodeNullable(String value) {
        return value == null ? NULL_FIELD : escape(value);
    }

    private String decodeNullable(String value) {
        return NULL_FIELD.equals(value) ? null : unescape(value);
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
