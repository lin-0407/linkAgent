package com.link.linkagent.memory;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.StubPromptService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryFactExtractorTest {

    @Test
    void shouldKeepFactOriginalAndScore() {
        SummaryFactExtractor extractor = new SummaryFactExtractor(new FixedLlmService(new SummaryFactExtraction(List.of(
                new SummaryFactPoint("user.preference.example_language", "偏好 Java 示例", "以后用 Java 举例", 85)
        ))), new StubPromptService());

        List<SummaryFactPoint> facts = extractFacts(extractor, List.of(new MemoryMessage("Human", "以后用 Java 举例")));

        assertThat(facts).hasSize(1);
        // 事实点与原文要同时保留：压缩后原文不再进上下文，D5 要求长期记忆承载原文
        assertThat(facts.get(0).storageContent()).isEqualTo("偏好 Java 示例\n原文：以后用 Java 举例");
        assertThat(facts.get(0).importance()).isEqualTo(85);
    }

    @Test
    void shouldDropFactsWithoutAnyContent() {
        SummaryFactExtractor extractor = new SummaryFactExtractor(new FixedLlmService(new SummaryFactExtraction(List.of(
                new SummaryFactPoint("user.profile.summary", "", "   ", 90),
                new SummaryFactPoint("project.profile.summary", "项目定位是个人工作台", "", 90)
        ))), new StubPromptService());

        List<SummaryFactPoint> facts = extractFacts(extractor, List.of(new MemoryMessage("Human", "项目定位是个人工作台")));

        // 空事实写进库里只会变成一条无意义记忆
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).storageContent()).isEqualTo("项目定位是个人工作台");
    }

    @Test
    void shouldFallbackImportanceWhenModelDoesNotGiveScore() {
        SummaryFactExtractor extractor = new SummaryFactExtractor(new FixedLlmService(new SummaryFactExtraction(List.of(
                new SummaryFactPoint("user.profile.summary", "用户是 Java 后端学习者", "我是学 Java 后端的", null),
                new SummaryFactPoint("project.profile.summary", "项目自托管", "这个项目自己部署", 0)
        ))), new StubPromptService());

        List<SummaryFactPoint> facts = extractFacts(extractor, List.of(new MemoryMessage("Human", "随便聊聊")));

        // D17.2 的兜底语义：抽不出分数用兜底值，不能因为缺字段变成 0 分被静默丢掉
        assertThat(facts).extracting(SummaryFactPoint::importance).containsExactly(40, 40);
    }

    @Test
    void shouldClampOutOfRangeImportance() {
        SummaryFactExtractor extractor = new SummaryFactExtractor(new FixedLlmService(new SummaryFactExtraction(List.of(
                new SummaryFactPoint("key", "事实一", "", 150),
                new SummaryFactPoint("key", "事实二", "", -8)
        ))), new StubPromptService());

        List<SummaryFactPoint> facts = extractFacts(extractor, List.of(new MemoryMessage("Human", "测试")));

        assertThat(facts).extracting(SummaryFactPoint::importance).containsExactly(100, 40);
    }

    @Test
    void shouldCapFactsPerCompression() {
        List<SummaryFactPoint> raw = new ArrayList<>();
        for (int index = 0; index < SummaryFactExtractor.MAX_FACTS_PER_COMPRESSION + 5; index++) {
            raw.add(new SummaryFactPoint("key", "事实 " + index, "", 85));
        }
        SummaryFactExtractor extractor = new SummaryFactExtractor(
                new FixedLlmService(new SummaryFactExtraction(raw)), new StubPromptService());

        List<SummaryFactPoint> facts = extractFacts(extractor, List.of(new MemoryMessage("Human", "测试")));

        // 250k token 的对话可能抽出上百条，单轮写入必须设上限（步骤 7 C14 的先行值）
        assertThat(facts).hasSize(SummaryFactExtractor.MAX_FACTS_PER_COMPRESSION);
    }

    @Test
    void shouldReturnEmptyWhenModelFails() {
        SummaryFactExtractor extractor = new SummaryFactExtractor(
                new FixedLlmService(new IllegalStateException("模型不可用")), new StubPromptService());

        SummaryFactExtractionResult result = extractor.extract(List.of(new MemoryMessage("Human", "测试")));

        // 抽取是增强能力：失败不能影响本轮压缩和回答；但调用方要能看出「失败」，否则压缩会照样推进游标
        assertThat(result.succeeded()).isFalse();
        assertThat(result.facts()).isEmpty();
    }

    @Test
    void shouldReportSuccessWhenModelReturnsNoFacts() {
        SummaryFactExtractor extractor = new SummaryFactExtractor(
                new FixedLlmService(new SummaryFactExtraction(List.of())), new StubPromptService());

        SummaryFactExtractionResult result = extractor.extract(List.of(new MemoryMessage("Human", "你好")));

        // 「模型说没有事实」是成功：游标可以推进，否则这段消息每轮都会被重抽
        assertThat(result.succeeded()).isTrue();
        assertThat(result.facts()).isEmpty();
    }

    @Test
    void shouldNotCallModelWhenMessagesAreEmpty() {
        FixedLlmService llmService = new FixedLlmService(new SummaryFactExtraction(List.of()));
        SummaryFactExtractor extractor = new SummaryFactExtractor(llmService, new StubPromptService());

        SummaryFactExtractionResult result = extractor.extract(List.of());
        assertThat(result.succeeded()).isTrue();
        assertThat(result.facts()).isEmpty();
        assertThat(llmService.callCount()).isZero();
    }

    /** 多数用例只关心事实点内容，这里统一断言调用成功后再取列表。 */
    private List<SummaryFactPoint> extractFacts(SummaryFactExtractor extractor, List<MemoryMessage> messages) {
        SummaryFactExtractionResult result = extractor.extract(messages);
        assertThat(result.succeeded()).isTrue();
        return result.facts();
    }

    private static class FixedLlmService extends LLMService {

        private final SummaryFactExtraction output;
        private final RuntimeException failure;
        private int callCount;

        FixedLlmService(SummaryFactExtraction output) {
            super();
            this.output = output;
            this.failure = null;
        }

        FixedLlmService(RuntimeException failure) {
            super();
            this.output = null;
            this.failure = failure;
        }

        @Override
        public <T> T chatStructured(String systemPrompt, String userMessage, Class<T> type) {
            callCount++;
            if (failure != null) {
                throw failure;
            }
            return type.cast(output);
        }

        int callCount() {
            return callCount;
        }
    }
}
