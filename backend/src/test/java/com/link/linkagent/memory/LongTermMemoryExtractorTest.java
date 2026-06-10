package com.link.linkagent.memory;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.StubPromptService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LongTermMemoryExtractorTest {

    @Test
    void shouldParseValidMemoryCandidate() {
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(new FixedLlmService("""
                {"shouldRemember":true,"memoryKey":"user.preference.language","content":"用户偏好使用 Java 示例"}
                """), new StubPromptService());

        Optional<LongTermMemoryCandidate> candidate = extractor.extract("以后用 Java 举例", "好的");

        assertThat(candidate).isPresent();
        assertThat(candidate.get().memoryKey()).isEqualTo("user.preference.language");
        assertThat(candidate.get().content()).isEqualTo("用户偏好使用 Java 示例");
    }

    @Test
    void shouldSkipWhenModelSaysNoNeedToRemember() {
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(new FixedLlmService("""
                {"shouldRemember":false,"memoryKey":"","content":""}
                """), new StubPromptService());

        Optional<LongTermMemoryCandidate> candidate = extractor.extract("今天天气怎么样", "我无法确认实时天气");

        assertThat(candidate).isEmpty();
    }

    @Test
    void shouldSkipWhenResponseIsInvalid() {
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(new FixedLlmService("不是合法 JSON"), new StubPromptService());

        Optional<LongTermMemoryCandidate> candidate = extractor.extract("hello", "hi");

        assertThat(candidate).isEmpty();
    }

    private static class FixedLlmService extends LLMService {

        private final String response;

        FixedLlmService(String response) {
            super();
            this.response = response;
        }

        @Override
        public String chat(String systemPrompt, String userMessage) {
            return response;
        }
    }
}
