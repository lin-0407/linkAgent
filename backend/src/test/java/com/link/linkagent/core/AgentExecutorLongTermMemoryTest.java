package com.link.linkagent.core;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.memory.InMemoryShortTermMemoryStore;
import com.link.linkagent.memory.LongTermMemory;
import com.link.linkagent.memory.LongTermMemoryMapper;
import com.link.linkagent.memory.LongTermMemoryRecord;
import com.link.linkagent.memory.ShortTermMemory;
import com.link.linkagent.memory.SummaryMemory;
import com.link.linkagent.memory.SummaryMemoryProperties;
import com.link.linkagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutorLongTermMemoryTest {

    @Test
    void shouldAppendDefaultUserLongTermMemoryToPrompt() {
        CapturingLlmService llmService = new CapturingLlmService();
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        AgentExecutor executor = new AgentExecutor(
                llmService,
                new ToolRegistry(List.of()),
                new ShortTermMemory(new InMemoryShortTermMemoryStore()),
                new SummaryMemory(new SummaryMemoryProperties(false, 8, 2), prompt -> new ChatResponse(List.of())),
                new LongTermMemory(mapper)
        );

        executor.run("session-1", "请根据我的偏好回答");

        assertThat(mapper.listUserId).isEqualTo("default");
        assertThat(llmService.lastUserMessage)
                .contains("Long-term memory:")
                .contains("user.preference.language: 用户偏好使用 Java")
                .contains("Human:请根据我的偏好回答");
    }

    @Test
    void shouldUseRequestUserIdWhenProvided() {
        CapturingLlmService llmService = new CapturingLlmService();
        FakeLongTermMemoryMapper mapper = new FakeLongTermMemoryMapper();
        AgentExecutor executor = new AgentExecutor(
                llmService,
                new ToolRegistry(List.of()),
                new ShortTermMemory(new InMemoryShortTermMemoryStore()),
                new SummaryMemory(new SummaryMemoryProperties(false, 8, 2), prompt -> new ChatResponse(List.of())),
                new LongTermMemory(mapper)
        );

        executor.run("session-1", " user-1 ", "hello");

        assertThat(mapper.listUserId).isEqualTo("user-1");
    }

    private static class CapturingLlmService extends LLMService {

        private String lastUserMessage;

        CapturingLlmService() {
            super();
        }

        @Override
        public String chat(String systemPrompt, String userMessage) {
            this.lastUserMessage = userMessage;
            return """
                    Thought:我现在已经掌握了所需信息
                    Final Answer:好的
                    """;
        }
    }

    private static class FakeLongTermMemoryMapper implements LongTermMemoryMapper {

        private String listUserId;

        @Override
        public int upsert(LongTermMemoryRecord record) {
            return 1;
        }

        @Override
        public Optional<LongTermMemoryRecord> findByKey(String userId, String memoryKey) {
            return Optional.empty();
        }

        @Override
        public List<LongTermMemoryRecord> listByUser(String userId, int limit) {
            this.listUserId = userId;
            LongTermMemoryRecord record = new LongTermMemoryRecord();
            record.setUserId(userId);
            record.setMemoryKey("user.preference.language");
            record.setContent("用户偏好使用 Java");
            return List.of(record);
        }
    }
}
