package com.link.linkagent.memory;

import com.link.linkagent.controller.AgentController;
import com.link.linkagent.core.AgentExecutor;
import com.link.linkagent.memory.model.ConversationMessageRecord;
import com.link.linkagent.memory.model.ConversationSessionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationHistoryTest {
    private final ConversationSessionMapper mapper = mock(ConversationSessionMapper.class);
    private final ShortTermMemory memory = new ShortTermMemory(new InMemoryShortTermMemoryStore());
    private final ConversationHistory history = new ConversationHistory(mapper, memory);

    @BeforeEach
    void setUp() {
        when(mapper.findSessionById("session")).thenReturn(Optional.of(session("default")));
        when(mapper.listSessionsByUser(eq("default"), anyInt())).thenReturn(List.of(session("default")));
    }

    @Test
    void coldCacheRestoresMessagesAndPersistentSessionList() {
        when(mapper.listMessagesBySession("session")).thenReturn(List.of(
                message(1, "user", "之前的问题"), message(2, "assistant", "之前的回答")));

        assertThat(history.listSessions("default")).singleElement().satisfies(item -> {
            assertThat(item.sessionId()).isEqualTo("session");
            assertThat(item.preview()).isEqualTo("之前的回答");
            assertThat(item.messageCount()).isEqualTo(2);
        });
        assertThat(history.load("session", "default").messages()).extracting(MemoryMessage::content)
                .containsExactly("之前的问题", "之前的回答");
        assertThat(memory.getRecentMessages("session")).hasSize(2);
        memory.append("session", "Human", "新问题", 3L);
        assertThat(history.load("session", "default").messages()).extracting(MemoryMessage::messageId)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void checkpointRestoresRetainedTailThatWasInsertedBeforeSummary() {
        ConversationMessageRecord summary = message(5, "summary", "压缩摘要");
        summary.setToolArguments("{\"compressedThroughMessageId\":2}");
        when(mapper.findLatestSummary("session")).thenReturn(Optional.of(summary));
        when(mapper.listMessagesBySession("session")).thenReturn(List.of(message(1, "user", "已压缩问题"),
                message(2, "assistant", "已压缩回答"), message(3, "user", "保留问题"),
                message(4, "assistant", "保留回答"), summary, message(6, "user", "摘要后追加")));

        ConversationHistory.Snapshot restored = history.load("session", "default");
        assertThat(restored.summary()).isSameAs(summary);
        assertThat(restored.messages()).extracting(MemoryMessage::messageId).containsExactly(3L, 4L, 6L);
        assertThat(history.load("session", "default").messages()).isEqualTo(restored.messages());
    }

    @Test
    void legacyCheckpointUsesCompleteOriginalsWithoutDuplicatingSummary() {
        ConversationMessageRecord summary = message(5, "summary", "旧摘要");
        when(mapper.findLatestSummary("session")).thenReturn(Optional.of(summary));
        when(mapper.listMessagesBySession("session")).thenReturn(List.of(message(1, "user", "早期问题"),
                message(2, "assistant", "早期回答"), message(3, "user", "保留问题"),
                message(4, "assistant", "保留回答"), summary));
        memory.append("session", "Human", "保留问题", 3L);
        memory.append("session", "AI", "保留回答", 4L);

        ConversationHistory.Snapshot restored = history.load("session", "default");
        assertThat(restored.ignoreLegacySummary()).isTrue();
        assertThat(restored.summary()).isNull();
        assertThat(restored.messages()).extracting(MemoryMessage::messageId).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void restoresToolRolesPairingArgumentsAndOrder() {
        ConversationMessageRecord call = message(2, "assistant", "");
        call.setToolName("lookup"); call.setToolCallId("call-1"); call.setToolArguments("参数");
        ConversationMessageRecord result = message(3, "tool", "工具结果");
        result.setToolName("lookup"); result.setToolCallId("call-1");
        when(mapper.listMessagesBySession("session")).thenReturn(List.of(message(1, "user", "问题"), call,
                result, message(4, "assistant", "回答")));

        var restored = history.load("session", "default").messages();
        assertThat(restored).extracting(MemoryMessage::role).containsExactly("Human", "AI", "Tool", "AI");
        assertThat(restored.get(1).isToolCall()).isTrue();
        assertThat(restored.get(1).promptText()).isEqualTo("ToolCall: lookup(参数)");
        assertThat(restored.get(2).toolCallId()).isEqualTo(restored.get(1).toolCallId());
        AgentController controller = new AgentController(mock(AgentExecutor.class), memory, mapper);
        assertThat(controller.sessionMessages("session", null)).extracting(item -> item.content())
                .containsExactly("问题", "回答");
    }

    @Test
    void refusesDifferentOwnerEvenWhenCacheHasMessages() {
        when(mapper.findSessionById("session")).thenReturn(Optional.of(session("someone-else")));
        memory.append("session", "Human", "私有历史");
        assertThatThrownBy(() -> history.load("session", "default"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404));
        assertThat(history.listSessions("another-user")).isEmpty();
    }

    @Test
    void emptySessionRestoresWithoutInventingMessages() {
        when(mapper.listMessagesBySession("session")).thenReturn(List.of());
        assertThat(history.load("session", "default").messages()).isEmpty();
        assertThat(history.listSessions("default")).singleElement().satisfies(item ->
                assertThat(item.messageCount()).isZero());
    }

    @Test
    void restoreDoesNotReplaceConcurrentAppend() {
        memory.append("session", "Human", "已经追加", 9L);
        memory.mergeRestoredMessages("session", List.of(new MemoryMessage("Human", "旧副本", 1L)));
        assertThat(memory.getRecentMessages("session")).extracting(MemoryMessage::content).containsExactly("旧副本", "已经追加");
    }

    @Test
    void concurrentAppendDuringDatabaseReadKeepsHistoryCompleteOnNextLoad() {
        when(mapper.listMessagesBySession("session")).thenAnswer(call -> {
            memory.append("session", "Human", "恢复期间追加", 3L);
            return List.of(message(1, "user", "早期问题"), message(2, "assistant", "早期回答"));
        });

        assertThat(history.load("session", "default").messages()).extracting(MemoryMessage::messageId)
                .containsExactly(1L, 2L, 3L);
        assertThat(history.load("session", "default").messages()).extracting(MemoryMessage::messageId)
                .containsExactly(1L, 2L, 3L);
        assertThat(memory.getRecentMessages("session")).extracting(MemoryMessage::messageId)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void repeatedRestoreDeduplicatesPersistedMessagesAndKeepsUnpersistedTail() {
        var persisted = new MemoryMessage("Human", "已落库", 1L);
        memory.append("session", persisted);
        memory.append("session", "Human", "未落库临时消息");
        memory.mergeRestoredMessages("session", List.of(persisted));
        memory.mergeRestoredMessages("session", List.of(persisted));
        assertThat(memory.getRecentMessages("session")).extracting(MemoryMessage::content)
                .containsExactly("已落库", "未落库临时消息");
    }

    private ConversationSessionRecord session(String user) {
        ConversationSessionRecord session = new ConversationSessionRecord();
        session.setSessionId("session"); session.setUserId(user); session.setTitle("会话标题");
        return session;
    }

    private ConversationMessageRecord message(long id, String role, String content) {
        ConversationMessageRecord message = new ConversationMessageRecord();
        message.setId(id); message.setSessionId("session"); message.setUserId("default");
        message.setRole(role); message.setContent(content);
        return message;
    }
}
