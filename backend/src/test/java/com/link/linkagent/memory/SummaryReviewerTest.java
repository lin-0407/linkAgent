package com.link.linkagent.memory;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.StubPromptService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryReviewerTest {

    private static final List<MemoryMessage> MESSAGES = List.of(new MemoryMessage("Human", "以后用 Java 举例"));

    /** 上一版摘要：累积合并后新摘要会把它带过来，复核必须以它为基准之一。 */
    private static final String EXISTING_SUMMARY = "用户长期只用 Java 写示例。";

    @Test
    void shouldFailBlankSummaryByRuleWithoutModelCall() {
        FixedLlmService llmService = new FixedLlmService((SummaryReviewOutput) null);
        SummaryReviewer reviewer = new SummaryReviewer(llmService, new StubPromptService());

        SummaryReviewResult result = reviewer.review("   ", EXISTING_SUMMARY, MESSAGES);

        assertThat(result.passed()).isFalse();
        // 规则能判定的问题不再花一次模型调用
        assertThat(llmService.callCount()).isZero();
        assertThat(result.issues()).contains("摘要为空");
    }

    @Test
    void shouldFailOverlongSummaryOnlyWhenLimitConfigured() {
        FixedLlmService llmService = new FixedLlmService((SummaryReviewOutput) null);
        SummaryReviewer reviewer = new SummaryReviewer(llmService, new StubPromptService(), 5000);

        SummaryReviewResult result = reviewer.review(
                "字".repeat(5001), EXISTING_SUMMARY, MESSAGES);

        assertThat(result.passed()).isFalse();
        assertThat(llmService.callCount()).isZero();
        assertThat(result.issues().get(0)).contains("字上限");
    }

    @Test
    void shouldAllowLongSummaryWhenNoLimitConfigured() {
        FixedLlmService llmService = new FixedLlmService(new SummaryReviewOutput(true, List.of(), List.of()));
        SummaryReviewer reviewer = new SummaryReviewer(llmService, new StubPromptService());

        SummaryReviewResult result = reviewer.review("字".repeat(5001), EXISTING_SUMMARY, MESSAGES);

        // 默认 0 = 不限制：长摘要不再被规则层直接打回，交给模型复核内容是否漏抽/编造
        assertThat(result.passed()).isTrue();
        assertThat(llmService.callCount()).isEqualTo(1);
    }

    @Test
    void shouldFailMarkdownFenceByRule() {
        SummaryReviewer reviewer = new SummaryReviewer(
                new FixedLlmService((SummaryReviewOutput) null), new StubPromptService());

        SummaryReviewResult result = reviewer.review("```摘要```", EXISTING_SUMMARY, MESSAGES);

        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anySatisfy(issue -> assertThat(issue).contains("Markdown 代码块"));
    }

    @Test
    void shouldPassWhenModelSaysOk() {
        SummaryReviewer reviewer = new SummaryReviewer(
                new FixedLlmService(new SummaryReviewOutput(true, List.of(), List.of())), new StubPromptService());

        SummaryReviewResult result = reviewer.review("用户在对话里要求以后用 Java 举例。", EXISTING_SUMMARY, MESSAGES);

        assertThat(result.passed()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void shouldCollectMissingAndFabricatedIssues() {
        SummaryReviewer reviewer = new SummaryReviewer(new FixedLlmService(
                new SummaryReviewOutput(false, List.of("用户要求示例用 Java"), List.of("用户说自己写 Python"))),
                new StubPromptService());

        SummaryReviewResult result = reviewer.review("用户在对话里提到编程语言。", EXISTING_SUMMARY, MESSAGES);

        assertThat(result.passed()).isFalse();
        assertThat(result.issues())
                .contains("漏抽：用户要求示例用 Java")
                .contains("编造：用户说自己写 Python");
    }

    @Test
    void shouldTreatSelfContradictoryOutputAsFail() {
        SummaryReviewer reviewer = new SummaryReviewer(
                new FixedLlmService(new SummaryReviewOutput(true, List.of(), List.of("原文没有的结论"))),
                new StubPromptService());

        SummaryReviewResult result = reviewer.review("摘要里有原文没有的结论。", EXISTING_SUMMARY, MESSAGES);

        // 模型说通过却同时列了问题：以列出的问题为准，按不通过处理
        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).contains("编造：原文没有的结论");
    }

    @Test
    void shouldFailWhenReviewCallThrows() {
        SummaryReviewer reviewer = new SummaryReviewer(
                new FixedLlmService(new IllegalStateException("模型不可用")), new StubPromptService());

        SummaryReviewResult result = reviewer.review("用户在对话里要求以后用 Java 举例。", EXISTING_SUMMARY, MESSAGES);

        // 复核不可用时宁可不裁剪（fail-closed）：摘要是被压缩消息的唯一载体
        assertThat(result.passed()).isFalse();
        assertThat(result.issues().get(0)).contains("复核调用失败");
    }

    @Test
    void shouldGiveModelBothExistingSummaryAndMessages() {
        AtomicReference<String> capturedUserMessage = new AtomicReference<>();
        SummaryReviewer reviewer = new SummaryReviewer(
                new FixedLlmService(new SummaryReviewOutput(true, List.of(), List.of()), capturedUserMessage),
                new ReviewPromptService());

        reviewer.review("用户要求以后用 Java 举例。", EXISTING_SUMMARY, MESSAGES);

        // 2026-09-19 实测缺陷：只给本次消息时，复核器会把从上一版摘要继承的真实信息判成编造，
        // 重生成提示词进而让模型把真实事实当成编造剔除。这里锁住「两个基准都要给」的契约。
        assertThat(capturedUserMessage.get())
                .contains(EXISTING_SUMMARY)
                .contains("用户要求以后用 Java 举例。")
                .contains("Human: 以后用 Java 举例");
    }

    @Test
    void shouldMarkMissingExistingSummaryExplicitly() {
        AtomicReference<String> capturedUserMessage = new AtomicReference<>();
        SummaryReviewer reviewer = new SummaryReviewer(
                new FixedLlmService(new SummaryReviewOutput(true, List.of(), List.of()), capturedUserMessage),
                new ReviewPromptService());

        reviewer.review("第一次压缩的摘要。", null, MESSAGES);

        // 上一版摘要为空时要显式写「（无）」，避免模型把空段当成「没有可比对的基准」而放宽判断
        assertThat(capturedUserMessage.get()).contains("（无）");
    }

    @Test
    void shouldReuseGenerationPrefixForReview() {
        AtomicReference<List<org.springframework.ai.chat.messages.Message>> capturedMessages = new AtomicReference<>();
        AtomicReference<String> capturedSystem = new AtomicReference<>();
        LLMService llm = new LLMService() {
            @Override
            public <T> T chatStructuredWithPrefixReuse(String systemPrompt,
                                                       List<org.springframework.ai.chat.messages.Message> messages,
                                                       Class<T> type) {
                capturedSystem.set(systemPrompt);
                capturedMessages.set(messages);
                return type.cast(new SummaryReviewOutput(true, List.of(), List.of()));
            }
        };
        SummaryReviewer reviewer = new SummaryReviewer(llm, new StubPromptService());
        List<org.springframework.ai.chat.messages.Message> generationMessages = List.of(
                new org.springframework.ai.chat.messages.SystemMessage("AGENT-SYSTEM"),
                new org.springframework.ai.chat.messages.UserMessage("前缀原文"));
        SummaryGeneration generation = new SummaryGeneration("新摘要", "AGENT-SYSTEM", generationMessages);

        SummaryReviewResult result = reviewer.review(generation, EXISTING_SUMMARY, MESSAGES);

        assertThat(result.passed()).isTrue();
        // system 必须与生成调用一致；messages 里不能再带 SystemMessage，否则 LLMService 会注入成第二条 system，
        // 渲染出的 prompt 从第二条消息开始就错位，前缀缓存失效（2026-10-18 实测）。
        assertThat(capturedSystem.get()).isEqualTo("AGENT-SYSTEM");
        assertThat(capturedMessages.get()).hasSize(generationMessages.size() + 1);
        assertThat(capturedMessages.get().get(0)).isEqualTo(generationMessages.get(1));
        assertThat(capturedMessages.get()).noneMatch(
                message -> message instanceof org.springframework.ai.chat.messages.SystemMessage);
        // 待复核摘要必须作为 assistant 消息出现，否则复核模型看不到摘要正文
        assertThat(capturedMessages.get().get(1).getText()).isEqualTo("新摘要");
        assertThat(capturedMessages.get().get(2)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
    }

    @Test
    void shouldReplayReviewWithGenerationEnvelope() {
        AtomicReference<com.link.linkagent.llm.ChatRequestEnvelope> capturedEnvelope = new AtomicReference<>();
        AtomicReference<List<org.springframework.ai.chat.messages.Message>> capturedMessages = new AtomicReference<>();
        LLMService llm = new LLMService() {
            @Override
            public <T> com.link.linkagent.llm.StructuredCallResult<T> replayStructured(
                    com.link.linkagent.llm.ChatRequestEnvelope envelope,
                    List<org.springframework.ai.chat.messages.Message> messages, Class<T> type,
                    Integer maxOutputTokens) {
                capturedEnvelope.set(envelope);
                capturedMessages.set(messages);
                return new com.link.linkagent.llm.StructuredCallResult<>(
                        type.cast(new SummaryReviewOutput(true, List.of(), List.of())), 10, 5, 15, 1L);
            }
        };
        SummaryReviewer reviewer = new SummaryReviewer(llm, new StubPromptService());
        List<org.springframework.ai.chat.messages.Message> generationMessages = List.of(
                new org.springframework.ai.chat.messages.UserMessage("上一轮真实请求前缀"));
        com.link.linkagent.llm.ChatRequestEnvelope envelope =
                com.link.linkagent.llm.ChatRequestEnvelope.main("AGENT-SYSTEM", generationMessages, null);
        SummaryGeneration generation = new SummaryGeneration("新摘要", "AGENT-SYSTEM", generationMessages, envelope);

        SummaryReviewResult result = reviewer.review(generation, EXISTING_SUMMARY, MESSAGES);

        assertThat(result.passed()).isTrue();
        // 复核必须用生成调用的同一个信封重放，否则 tools/thinking 形状不同，前缀缓存照样失效。
        assertThat(capturedEnvelope.get()).isSameAs(envelope);
        assertThat(capturedMessages.get()).hasSize(generationMessages.size() + 2);
        assertThat(capturedMessages.get().get(0).getText()).isEqualTo("上一轮真实请求前缀");
        assertThat(capturedMessages.get().get(1).getText()).isEqualTo("新摘要");
    }

    /**
     * 真实用户提示词模板在数据库里，StubPromptService 返回的是占位串、没有 {existingSummary} 等占位符，
     * 无法验证「两个基准是否都传给了模型」。这里给用户提示词一份带占位符的模板。
     */
    private static class ReviewPromptService extends StubPromptService {
        @Override
        public String get(String key) {
            if ("summary_review.user".equals(key)) {
                return "上一版摘要：{existingSummary}\n新摘要：{summary}\n本次消息：{messages}";
            }
            return super.get(key);
        }
    }

    private static class FixedLlmService extends LLMService {

        private final SummaryReviewOutput output;
        private final RuntimeException failure;
        private final AtomicReference<String> capturedUserMessage;
        private int callCount;

        FixedLlmService(SummaryReviewOutput output) {
            this(output, null);
        }

        FixedLlmService(SummaryReviewOutput output, AtomicReference<String> capturedUserMessage) {
            super();
            this.output = output;
            this.failure = null;
            this.capturedUserMessage = capturedUserMessage;
        }

        FixedLlmService(RuntimeException failure) {
            super();
            this.output = null;
            this.failure = failure;
            this.capturedUserMessage = null;
        }

        @Override
        public <T> T chatStructured(String systemPrompt, String userMessage, Class<T> type) {
            callCount++;
            if (capturedUserMessage != null) {
                capturedUserMessage.set(userMessage);
            }
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
