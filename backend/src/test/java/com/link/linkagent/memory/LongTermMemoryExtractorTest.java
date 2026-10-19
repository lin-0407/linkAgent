package com.link.linkagent.memory;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.StubPromptService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LongTermMemoryExtractorTest {

    @Test
    void shouldExtractCandidateWithImportanceFromBatch() {
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(new FixedLlmService("""
                {"shouldRemember":true,"memoryKey":"user.preference.language","content":"用户偏好使用 Java 示例","importance":90}
                """), new StubPromptService());

        LongTermMemoryExtractionResult result = extractor.extract(List.of(
                new MemoryMessage("Human", "以后用 Java 举例", 1L),
                new MemoryMessage("AI", "好的", 2L)
        ));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.hasCandidates()).isTrue();
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.memoryKey()).isEqualTo("user.preference.language");
            assertThat(candidate.content()).isEqualTo("用户偏好使用 Java 示例");
            assertThat(candidate.importance()).isEqualTo(90);
        });
    }

    @Test
    void shouldTreatNoNeedToRememberAsSuccessWithoutCandidate() {
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(new FixedLlmService("""
                {"shouldRemember":false,"memoryKey":"","content":"","importance":0}
                """), new StubPromptService());

        LongTermMemoryExtractionResult result = extractor.extract(List.of(new MemoryMessage("Human", "今天天气怎么样", 1L)));

        // 模型明确说不用记 → 调用成功、没有候选，调用方可以推进游标
        assertThat(result.succeeded()).isTrue();
        assertThat(result.hasCandidates()).isFalse();
    }

    @Test
    void shouldReportFailureWhenResponseBreaksContract() {
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(new FixedLlmService("不是合法 JSON"), new StubPromptService());

        LongTermMemoryExtractionResult result = extractor.extract(List.of(new MemoryMessage("Human", "hello", 1L)));

        // 没有 shouldRemember 说明模型没按契约输出：按失败处理，游标不能推进，否则这批消息被永久跳过
        assertThat(result.succeeded()).isFalse();
        assertThat(result.hasCandidates()).isFalse();
    }

    @Test
    void shouldReportFailureWhenRememberTrueButFieldsAreEmpty() {
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(new FixedLlmService("""
                {"shouldRemember":true,"memoryKey":"","content":"","importance":90}
                """), new StubPromptService());

        LongTermMemoryExtractionResult result = extractor.extract(List.of(new MemoryMessage("Human", "hello", 1L)));

        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void shouldFallbackImportanceWhenModelDoesNotGiveScore() {
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(new FixedLlmService("""
                {"shouldRemember":true,"memoryKey":"user.profile.summary","content":"用户是 Java 后端学习者"}
                """), new StubPromptService());

        LongTermMemoryExtractionResult result = extractor.extract(List.of(new MemoryMessage("Human", "我是学 Java 后端的", 1L)));

        // 缺分数或给了越界分数时按 D17.2 的兜底 40，不能写 0 分让这条记忆在步骤 8 的排序里消失
        assertThat(result.candidates().get(0).importance()).isEqualTo(LongTermMemoryExtractor.DEFAULT_IMPORTANCE);
    }

    @Test
    void shouldClampOutOfRangeImportance() {
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(new FixedLlmService("""
                {"shouldRemember":true,"memoryKey":"user.profile.summary","content":"用户是 Java 后端学习者","importance":150}
                """), new StubPromptService());

        assertThat(extractor.extract(List.of(new MemoryMessage("Human", "测试", 1L))).candidates().get(0).importance())
                .isEqualTo(100);
    }

    @Test
    void shouldRenderWholeBatchIntoPrompt() {
        FixedLlmService llmService = new FixedLlmService("""
                {"shouldRemember":false,"memoryKey":"","content":""}
                """);
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(llmService, new RenderingPromptService());

        extractor.extract(List.of(
                new MemoryMessage("Human", "第一轮问题", 1L),
                new MemoryMessage("AI", "第一轮回答", 2L),
                new MemoryMessage("user", "第二轮问题", 3L)
        ));

        // 批量抽取的输入必须是整批消息：多轮一起看，代词和跨轮指代才有上下文可解（D7）
        assertThat(llmService.lastUserMessage)
                .contains("用户: 第一轮问题")
                .contains("助手: 第一轮回答")
                .contains("用户: 第二轮问题");
    }

    @Test
    void shouldNotCallModelWhenMessagesAreEmpty() {
        FixedLlmService llmService = new FixedLlmService("{}");
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(llmService, new StubPromptService());

        LongTermMemoryExtractionResult result = extractor.extract(List.of());

        assertThat(result.succeeded()).isTrue();
        assertThat(llmService.callCount()).isZero();
    }

    @Test
    void shouldParseMultipleCandidatesInOneResponse() {
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(new FixedLlmService("{}"), new StubPromptService());

        LongTermMemoryExtractionResult result = extractor.parseResult("""
                {"shouldRemember":true,"memories":[
                  {"memoryKey":"user.preference.example_language","content":"示例默认用 Java","importance":88},
                  {"memoryKey":"project.profile.summary","content":"LinkAgent 是个人工作台","importance":86}
                ]}
                """);

        // 单条契约会漏掉同一批里的其它事实，而游标把这批一次性推过后就再也不会回看（步骤 7 的修复点）
        assertThat(result.candidates()).extracting(LongTermMemoryCandidate::content)
                .containsExactly("示例默认用 Java", "LinkAgent 是个人工作台");
    }

    @Test
    void shouldKeepHighestImportanceCandidateForSameKey() {
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(new FixedLlmService("{}"), new StubPromptService());

        LongTermMemoryExtractionResult result = extractor.parseResult("""
                {"shouldRemember":true,"memories":[
                  {"memoryKey":"user.profile.summary","content":"用户在做实习","importance":60},
                  {"memoryKey":"user.profile.summary","content":"用户在做 AI 应用实习","importance":85}
                ]}
                """);

        assertThat(result.candidates()).singleElement()
                .extracting(LongTermMemoryCandidate::content).isEqualTo("用户在做 AI 应用实习");
    }

    @Test
    void shouldKeepDifferentPersonalEventsUnderSameKey() {
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(new FixedLlmService("{}"), new StubPromptService());

        LongTermMemoryExtractionResult result = extractor.parseResult("""
                {"shouldRemember":true,"memories":[
                  {"memoryKey":"user.event","content":"用户首次保养汽车后，车辆 GPS 出现故障","importance":88},
                  {"memoryKey":"user.event","content":"用户 2 月先维护自行车，后维护汽车","importance":86},
                  {"memoryKey":"user.event","content":"用户首次保养汽车后，车辆 GPS 出现故障","importance":80}
                ]}
                """);

        // 同类的不同事件都要留下；重复描述同一事件才去重，否则跨会话的先后问答会缺事实。
        assertThat(result.candidates()).extracting(LongTermMemoryCandidate::content)
                .containsExactly("用户首次保养汽车后，车辆 GPS 出现故障", "用户 2 月先维护自行车，后维护汽车");
    }

    @Test
    void shouldSkipIncompleteCandidateAndKeepTheRest() {
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(new FixedLlmService("{}"), new StubPromptService());

        LongTermMemoryExtractionResult result = extractor.parseResult("""
                {"shouldRemember":true,"memories":[
                  {"memoryKey":"user.profile.summary","content":"","importance":60},
                  {"memoryKey":"project.constraint.summary","content":"不要引入多租户","importance":82}
                ]}
                """);

        // 一条坏候选不该让整批失败：其它完整候选照常入库，坏的那条下轮还有机会被重新抽出
        assertThat(result.succeeded()).isTrue();
        assertThat(result.candidates()).singleElement()
                .extracting(LongTermMemoryCandidate::memoryKey).isEqualTo("project.constraint.summary");
    }

    @Test
    void shouldRenderObservationDateIntoPrompt() {
        FixedLlmService llmService = new FixedLlmService("""
                {"shouldRemember":false,"memories":[]}
                """);
        LongTermMemoryExtractor extractor = new LongTermMemoryExtractor(llmService, new RenderingPromptService());

        extractor.extract(List.of(new MemoryMessage("Human", "上周把方案定下来了", 1L)),
                java.time.LocalDate.of(2026, 9, 19));

        // 观察日期必须传进提示词，否则模型无法把「上周」换算成绝对日期（C16）
        assertThat(llmService.lastUserMessage).contains("observationDate: 2026-09-19");
    }

    private static class FixedLlmService extends LLMService {

        private final String response;
        private int callCount;
        private String lastUserMessage;

        FixedLlmService(String response) {
            super();
            this.response = response;
        }

        @Override
        public String chat(String systemPrompt, String userMessage) {
            callCount++;
            lastUserMessage = userMessage;
            return response;
        }

        int callCount() {
            return callCount;
        }
    }
}
