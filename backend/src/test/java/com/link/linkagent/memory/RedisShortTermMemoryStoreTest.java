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

        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
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
    void shouldTrimMessagesByConfiguredWindowSize() {
        store.append("session-1", new MemoryMessage("Human", "first"), 2);
        store.append("session-1", new MemoryMessage("AI", "second"), 2);
        store.append("session-1", new MemoryMessage("Human", "third"), 2);

        List<MemoryMessage> messages = store.getRecentMessages("session-1");

        assertThat(messages)
                .extracting(MemoryMessage::content)
                .containsExactly("second", "third");
    }

    @Test
    void shouldKeepEscapedMessageContentReadable() {
        String content = "line-1\nline-2\twith-tab\\slash";

        store.append("session-escape", new MemoryMessage("Human", content), 10);

        assertThat(store.getRecentMessages("session-escape"))
                .containsExactly(new MemoryMessage("Human", content));
    }

    @Test
    void shouldReplaceMessages() {
        store.append("session-1", new MemoryMessage("Human", "first"), 10);
        store.append("session-1", new MemoryMessage("AI", "second"), 10);

        store.replaceMessages("session-1", List.of(new MemoryMessage("Human", "latest")));

        assertThat(store.getRecentMessages("session-1"))
                .containsExactly(new MemoryMessage("Human", "latest"));
    }

    @Test
    void shouldListSessionsWithLatestPreviewAndMessageCount() {
        store.append("session-short", new MemoryMessage("Human", "hello"), 10);
        store.append("session-long", new MemoryMessage("Human", "first"), 10);
        store.append("session-long", new MemoryMessage("AI", "second response"), 10);

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
