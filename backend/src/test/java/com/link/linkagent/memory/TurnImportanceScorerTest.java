package com.link.linkagent.memory;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.StubPromptService;
import com.link.linkagent.prompt.service.PromptService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

class TurnImportanceScorerTest {

    @Test
    void shouldReturnModelScore() {
        FixedLlmService llmService = new FixedLlmService(new TurnImportanceScore(85, "用户给了一条长期约束"));
        TurnImportanceScorer scorer = new TurnImportanceScorer(llmService, new RenderingPromptService());

        assertThat(scorer.score("以后都按这个约束来", "好的")).isEqualTo(85);
        assertThat(llmService.lastUserMessage)
                .contains("以后都按这个约束来")
                .contains("好的");
    }

    @Test
    void shouldFallbackWhenModelCallFails() {
        FixedLlmService llmService = new FixedLlmService(new IllegalStateException("模型不可用"));
        TurnImportanceScorer scorer = new TurnImportanceScorer(llmService, new StubPromptService());

        // 打分失败必须给兜底分：预算如果永远不扣，长期记忆就再也不会触发
        assertThat(scorer.score("随便聊聊", "嗯")).isEqualTo(TurnImportanceScorer.FALLBACK_IMPORTANCE);
    }

    @Test
    void shouldFallbackWhenModelDoesNotGiveScore() {
        FixedLlmService llmService = new FixedLlmService(new TurnImportanceScore(null, ""));
        TurnImportanceScorer scorer = new TurnImportanceScorer(llmService, new StubPromptService());

        assertThat(scorer.score("随便聊聊", "嗯")).isEqualTo(TurnImportanceScorer.FALLBACK_IMPORTANCE);
    }

    @Test
    void shouldClampOutOfRangeScore() {
        // 越界分数直接把预算搞乱：150 分几乎每轮都触发，0 分永远不触发
        assertThat(new TurnImportanceScorer(new FixedLlmService(new TurnImportanceScore(150, "")), new StubPromptService())
                .score("a", "b")).isEqualTo(100);
        assertThat(new TurnImportanceScorer(new FixedLlmService(new TurnImportanceScore(0, "")), new StubPromptService())
                .score("a", "b")).isEqualTo(TurnImportanceScorer.FALLBACK_IMPORTANCE);
    }

    @Test
    void shouldFallbackWhenPromptIsMissing() {
        TurnImportanceScorer scorer = new TurnImportanceScorer(new FixedLlmService(new TurnImportanceScore(90, "")),
                new MissingPromptService());

        // 提示词种子没灌是部署错误：这里只保证不抛异常、不卡住对话，错误日志由实现负责
        assertThat(scorer.score("a", "b")).isEqualTo(TurnImportanceScorer.FALLBACK_IMPORTANCE);
    }

    @Test
    void shouldAllowLowThinkingForScoringAndKeepOutputLimit() {
        FixedLlmService llmService = new FixedLlmService(new TurnImportanceScore(85, ""));
        TurnImportanceScorer scorer = new TurnImportanceScorer(llmService, new StubPromptService());

        scorer.score("问题", "回答");

        // 作者改为辅助调用使用 low，不能再把这次请求强行关闭思考。
        assertThat(llmService.lastDisableThinking).isFalse();
        assertThat(llmService.lastMaxTokens).isEqualTo(TurnImportanceScorer.MAX_OUTPUT_TOKENS);
    }

    @Test
    void shouldFallbackWhenScoringCallHangsPastTimeout() {
        // 打分卡住必须按失败兜底：它拖住的是「回答已经出来、只等 done」的界面（2026-09-19 联调现象）
        TurnImportanceScorer scorer = new TurnImportanceScorer(new HangingLlmService(), new StubPromptService(), 1);
        long startedAt = System.nanoTime();

        int score = scorer.score("问题", "回答");

        assertThat(score).isEqualTo(TurnImportanceScorer.FALLBACK_IMPORTANCE);
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt)).isLessThanOrEqualTo(5);
    }

    /** 永远不返回的打分调用，用来验证超时兜底。 */
    private static class HangingLlmService extends LLMService {

        @Override
        public <T> T chatStructured(String systemPrompt, String userMessage, Class<T> type,
                                    boolean disableThinking, Integer maxTokens) {
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(5));
            throw new IllegalStateException("不会被调用到");
        }
    }

    private static class FixedLlmService extends LLMService {

        private final TurnImportanceScore output;
        private final RuntimeException failure;
        private String lastUserMessage;
        private boolean lastDisableThinking;
        private Integer lastMaxTokens;

        FixedLlmService(TurnImportanceScore output) {
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
        public <T> T chatStructured(String systemPrompt, String userMessage, Class<T> type,
                                    boolean disableThinking, Integer maxTokens) {
            lastUserMessage = userMessage;
            lastDisableThinking = disableThinking;
            lastMaxTokens = maxTokens;
            if (failure != null) {
                throw failure;
            }
            return type.cast(output);
        }
    }

    /** 模拟提示词种子缺失：PromptService 查不到 key 时抛 ResponseStatusException。 */
    private static class MissingPromptService extends PromptService {

        MissingPromptService() {
            super(null);
        }

        @Override
        public String get(String key) {
            throw new IllegalStateException("提示词模板 key=" + key + " 未配置");
        }
    }
}
