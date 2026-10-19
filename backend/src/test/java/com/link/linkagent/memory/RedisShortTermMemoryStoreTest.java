package com.link.linkagent.memory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RedisShortTermMemoryStoreTest {

    private static final String TEST_REDIS_HOST = "TEST_REDIS_HOST";
    private static final String TEST_REDIS_PORT = "TEST_REDIS_PORT";
    private static final String TEST_REDIS_PASSWORD = "TEST_REDIS_PASSWORD";
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.2-alpine");

    private static GenericContainer<?> redisContainer;
    private static String redisHost;
    private static int redisPort;
    private static String redisPassword;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisShortTermMemoryStore store;

    @BeforeAll
    static void resolveRedis() {
        Optional<String> externalHost = readConfig(TEST_REDIS_HOST);
        if (externalHost.isPresent()) {
            redisHost = externalHost.get();
            redisPort = readConfig(TEST_REDIS_PORT)
                    .map(Integer::parseInt)
                    .orElse(6379);
            redisPassword = readConfig(TEST_REDIS_PASSWORD).orElse(null);
            return;
        }

        try {
            redisContainer = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
            redisContainer.start();
            redisHost = redisContainer.getHost();
            redisPort = redisContainer.getMappedPort(6379);
        } catch (IllegalStateException exception) {
            Assumptions.assumeTrue(false, "No test Redis configured and Docker is not available");
        }
    }

    @AfterAll
    static void stopRedisContainer() {
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(redisHost, redisPort);
        if (redisPassword != null && !redisPassword.isBlank()) {
            configuration.setPassword(RedisPassword.of(redisPassword));
        }
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        flushRedis(redisTemplate);

        store = new RedisShortTermMemoryStore(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private void flushRedis(StringRedisTemplate redisTemplate) {
        RedisConnection connection = redisTemplate.getConnectionFactory().getConnection();
        try {
            connection.serverCommands().flushDb();
        } finally {
            connection.close();
        }
    }

    @Test
    void shouldAppendMessagesWithoutWindowTrimming() {
        store.append("session-1", new MemoryMessage("Human", "first"));
        store.append("session-1", new MemoryMessage("AI", "second"));
        store.append("session-1", new MemoryMessage("Human", "third"));

        List<MemoryMessage> messages = store.getRecentMessages("session-1");

        // 按条数裁剪（LTRIM）已删除：存储层只负责追加，裁剪由摘要压缩后的 replaceMessages 完成。
        assertThat(messages)
                .extracting(MemoryMessage::content)
                .containsExactly("first", "second", "third");
    }

    @Test
    void shouldKeepEscapedMessageContentReadable() {
        String content = "line-1\nline-2\twith-tab\\slash";

        store.append("session-escape", new MemoryMessage("Human", content));

        assertThat(store.getRecentMessages("session-escape"))
                .containsExactly(new MemoryMessage("Human", content));
    }

    @Test
    void shouldKeepMessageIdAcrossRoundTrip() {
        store.append("session-id", new MemoryMessage("Human", "带 id 的消息", 42L));

        // 消息 id 是长期记忆游标的比较基准（A6 / C4），Redis 往返后必须原样带回来
        assertThat(store.getRecentMessages("session-id"))
                .containsExactly(new MemoryMessage("Human", "带 id 的消息", 42L));
    }

    @Test
    void shouldRoundTripNativeToolCallPair() {
        MemoryMessage call = MemoryMessage.toolCall("AI", 7L, "call_1", "get_comments",
                "video=BV1xx,page=1");
        MemoryMessage result = MemoryMessage.toolResult(ShortTermMemory.ROLE_TOOL, 8L, "call_1",
                "get_comments", "共 42 条评论");

        store.append("session-tool", call);
        store.append("session-tool", result);

        // 工具调用与结果靠 toolCallId 配对：Redis 往返后字段不能丢，否则重启后配对关系断裂
        List<MemoryMessage> messages = store.getRecentMessages("session-tool");
        assertThat(messages).containsExactly(call, result);
        assertThat(messages.get(0).isToolCall()).isTrue();
        assertThat(messages.get(1).isToolResult()).isTrue();
        assertThat(messages.get(1).toolCallId()).isEqualTo(messages.get(0).toolCallId());
    }

    @Test
    void shouldReadLegacyValueWithoutMessageId() {
        // 旧数据没有消息 id 列（第一列直接是角色名）：这次改造不重写历史列表，读不出来等于丢历史
        redisTemplate.opsForList().rightPush("link-agent:memory:short-term:session-legacy", "Human\tlegacy content");

        assertThat(store.getRecentMessages("session-legacy"))
                .containsExactly(new MemoryMessage("Human", "legacy content"));
    }

    @Test
    void shouldReplaceMessages() {
        store.append("session-1", new MemoryMessage("Human", "first"));
        store.append("session-1", new MemoryMessage("AI", "second"));

        store.replaceMessages("session-1", List.of(new MemoryMessage("Human", "latest")));

        assertThat(store.getRecentMessages("session-1"))
                .containsExactly(new MemoryMessage("Human", "latest"));
    }

    @Test
    void shouldClearMessagesWhenReplacedWithEmptyList() {
        store.append("session-clear", new MemoryMessage("Human", "first"));
        store.append("session-clear", new MemoryMessage("AI", "second"));

        // 清空路径走的是脚本里「一条 ARGV 都没有」的分支：只删不写，不能因为空参数报错
        store.replaceMessages("session-clear", List.of());

        assertThat(store.getRecentMessages("session-clear")).isEmpty();
    }

    @Test
    void shouldNeverExposeEmptyOrPartialListWhileReplacing() throws InterruptedException {
        String sessionId = "session-replace-race";
        List<MemoryMessage> oldMessages = List.of(new MemoryMessage("Human", "old-1"), new MemoryMessage("AI", "old-2"));
        List<MemoryMessage> newMessages = List.of(
                new MemoryMessage("Human", "new-1"), new MemoryMessage("AI", "new-2"), new MemoryMessage("Human", "new-3"));
        store.replaceMessages(sessionId, oldMessages);

        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicInteger emptyReads = new AtomicInteger();
        AtomicInteger partialReads = new AtomicInteger();
        Thread reader = new Thread(() -> {
            while (!finished.get()) {
                int size = store.getRecentMessages(sessionId).size();
                // 替换期间读者只允许看到「替换前的完整列表」或「替换后的完整列表」
                if (size == 0) {
                    emptyReads.incrementAndGet();
                } else if (size != oldMessages.size() && size != newMessages.size()) {
                    partialReads.incrementAndGet();
                }
            }
        });
        reader.setDaemon(true);
        reader.start();
        try {
            for (int round = 0; round < 200; round++) {
                store.replaceMessages(sessionId, newMessages);
                store.replaceMessages(sessionId, oldMessages);
            }
        } finally {
            finished.set(true);
            reader.join(5000);
        }

        // 旧实现是 DEL + RPUSH 两条命令（F3）：读者会撞见空列表（并发读拿到「最近说了什么」为空）
        // 或半截列表（并发追加插在保留消息前面），这两种都必须为 0
        assertThat(emptyReads.get()).isZero();
        assertThat(partialReads.get()).isZero();
    }

    @Test
    void shouldListSessionsWithLatestPreviewAndMessageCount() {
        store.append("session-short", new MemoryMessage("Human", "hello"));
        store.append("session-long", new MemoryMessage("Human", "first"));
        store.append("session-long", new MemoryMessage("AI", "second response"));

        List<SessionInfo> sessions = store.listSessions();

        assertThat(sessions)
                .extracting(SessionInfo::sessionId)
                .containsExactly("session-long", "session-short");
        assertThat(sessions.getFirst().preview()).isEqualTo("second response");
        assertThat(sessions.getFirst().messageCount()).isEqualTo(2);
    }

    private static Optional<String> readConfig(String name) {
        String systemProperty = System.getProperty(name);
        if (systemProperty != null && !systemProperty.isBlank()) {
            return Optional.of(systemProperty);
        }
        String environment = System.getenv(name);
        if (environment != null && !environment.isBlank()) {
            return Optional.of(environment);
        }
        return Optional.empty();
    }
}
