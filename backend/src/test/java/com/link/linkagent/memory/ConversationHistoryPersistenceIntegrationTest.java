package com.link.linkagent.memory;

import com.link.linkagent.controller.AgentController;
import com.link.linkagent.core.AgentExecutor;
import com.link.linkagent.memory.model.ConversationMessageRecord;
import com.link.linkagent.memory.model.ConversationSessionRecord;
import com.link.linkagent.prompt.StubPromptService;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 真实 MySQL/MyBatis 验证消息和摘要边界恢复，全部数据使用本轮专用会话并在结束后清理。 */
class ConversationHistoryPersistenceIntegrationTest {
    private static GenericContainer<?> mysql;
    private static JdbcTemplate jdbc;
    private static ConversationSessionMapper mapper;
    private final List<String> createdSessions = new ArrayList<>();

    @BeforeAll
    static void setup() throws Exception {
        String url = System.getProperty("linkagent.persistence.jdbc-url");
        if (url == null) {
            mysql = new GenericContainer<>("mysql:8.4")
                    .withEnv("MYSQL_ROOT_PASSWORD", "bugfix-isolated-only")
                    .withEnv("MYSQL_ROOT_HOST", "%").withEnv("MYSQL_DATABASE", "link_agent")
                    .withLabel("linkagent.scope", "history-recovery-regression-test")
                    .withExposedPorts(3306)
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
            mysql.start();
            url = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                    + "/link_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        }
        var source = new DriverManagerDataSource(url, "root", "bugfix-isolated-only");
        jdbc = new JdbcTemplate(source);
        // 仅测试自己创建的容器需要初始化；显式复用的隔离库由调用者准备。
        if (mysql != null) {
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/init.sql"));
            }
        }
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ConversationSessionMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration); factory.afterPropertiesSet();
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ConversationSessionMapper.class);
    }

    @AfterEach
    void clearOwnData() {
        for (String id : createdSessions) {
            jdbc.update("DELETE FROM t_conversation_message WHERE session_id = ?", id);
            jdbc.update("DELETE FROM t_conversation_session WHERE session_id = ?", id);
        }
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) mysql.stop();
    }

    @Test
    void persistsAndRestoresSummaryWithTailFromBeforeCheckpointAndNativeToolPair() {
        String user = "history-" + UUID.randomUUID();
        String id = createSession(user);
        insert(id, user, "user", "被摘要的问题");
        ConversationMessageRecord compressed = insert(id, user, "assistant", "被摘要的回答");
        ConversationMessageRecord retained = insert(id, user, "user", "保留的问题");
        ConversationMessageRecord call = record(id, user, "assistant", "");
        call.setToolCallId("recovery-call"); call.setToolName("lookup"); call.setToolArguments("查询参数");
        mapper.insertMessage(call);
        ConversationMessageRecord result = record(id, user, "tool", "查询结果");
        result.setToolCallId("recovery-call"); result.setToolName("lookup"); mapper.insertMessage(result);
        ConversationMessageRecord answer = insert(id, user, "assistant", "保留的回答");
        SummaryMemory summaries = new SummaryMemory(new SummaryMemoryProperties(true, 256_000, 2),
                prompt -> new ChatResponse(List.of()), new StubPromptService(), null, null, mapper);
        summaries.recordCompactionCheckpoint(id, user, "摘要正文", 2, 300, 100, compressed.getId());
        ConversationMessageRecord checkpoint = mapper.findLatestSummary(id).orElseThrow();
        assertThat(checkpoint.getUserId()).isEqualTo(user);
        assertThat(ConversationHistory.compressedThrough(checkpoint)).isEqualTo(compressed.getId());
        assertThat(checkpoint.getId()).isGreaterThan(answer.getId());

        for (int restart = 0; restart < 2; restart++) {
            ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());
            ConversationHistory history = new ConversationHistory(mapper, memory);
            assertThat(history.listSessions(user)).extracting(SessionInfo::sessionId).containsExactly(id);
            ConversationHistory.Snapshot restored = history.load(id, user);
            assertThat(restored.summary().getContent()).isEqualTo("摘要正文");
            assertThat(restored.messages()).extracting(MemoryMessage::messageId)
                    .containsExactly(retained.getId(), call.getId(), result.getId(), answer.getId());
            assertThat(restored.messages()).extracting(MemoryMessage::role).containsExactly("Human", "AI", "Tool", "AI");
            assertThat(restored.messages().get(1).toolArguments()).isEqualTo("查询参数");
            assertThat(restored.messages().get(2).toolCallId()).isEqualTo("recovery-call");
            AgentController controller = new AgentController(mock(AgentExecutor.class), memory, mapper);
            assertThat(controller.sessionMessages(id, user)).extracting(item -> item.content())
                    .containsExactly("摘要正文", "保留的问题", "保留的回答");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_conversation_message WHERE session_id = ?", Integer.class, id))
                .isEqualTo(7);
    }

    @Test
    void restoresUncompressedAndLegacyHistoryAndCannotReplaceOwner() {
        String user = "history-" + UUID.randomUUID();
        String id = createSession(user);
        insert(id, user, "user", "原始问题"); insert(id, user, "assistant", "原始回答");
        ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());
        assertThat(new ConversationHistory(mapper, memory).load(id, user).messages()).hasSize(2);
        insert(id, user, "summary", "没有旧边界的摘要");
        ConversationHistory.Snapshot legacy = new ConversationHistory(mapper,
                new ShortTermMemory(new InMemoryShortTermMemoryStore())).load(id, user);
        assertThat(legacy.summary()).isNull();
        assertThat(legacy.messages()).extracting(MemoryMessage::content).containsExactly("原始问题", "原始回答");
        ConversationSessionRecord attemptedOverwrite = session(id, "wrong-owner");
        mapper.upsertSession(attemptedOverwrite);
        assertThat(mapper.findSessionById(id).orElseThrow().getUserId()).isEqualTo(user);
        assertThatThrownBy(() -> new ConversationHistory(mapper, memory).load(id, "wrong-owner"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private String createSession(String user) {
        String id = "history-" + UUID.randomUUID();
        createdSessions.add(id); mapper.upsertSession(session(id, user));
        return id;
    }

    private ConversationSessionRecord session(String id, String user) {
        ConversationSessionRecord session = new ConversationSessionRecord();
        session.setSessionId(id); session.setUserId(user); session.setTitle("恢复测试"); session.setStatus(0);
        session.setCreateTime(LocalDateTime.now()); session.setUpdateTime(LocalDateTime.now());
        return session;
    }

    private ConversationMessageRecord insert(String id, String user, String role, String content) {
        ConversationMessageRecord message = record(id, user, role, content); mapper.insertMessage(message); return message;
    }

    private ConversationMessageRecord record(String id, String user, String role, String content) {
        ConversationMessageRecord message = new ConversationMessageRecord();
        message.setSessionId(id); message.setUserId(user); message.setRole(role); message.setContent(content);
        message.setTokenCount(0); message.setCreateTime(LocalDateTime.now());
        return message;
    }
}
