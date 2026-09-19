package com.link.linkagent.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenCounter 的回归测试。
 *
 * <h3>期望值从哪来</h3>
 * 下面所有数字都是用同一份官方词表量出来的：Python 的 tokenizers 加载
 * {@code backend/src/main/resources/tokenizer/tokenizer.json}，{@code add_special_tokens=False}、不截断。
 * Java 与 Python 在正文上计数一致这一点已在记忆系统重构设计文档 9.3 第八节用真实 API 调用验证过。
 *
 * <h3>为什么必须有这些用例</h3>
 * <ol>
 *   <li>词表或原生库加载失败时，{@link TokenCounter} 会静默降级成「字符数 × 0.6」估算，256k 阈值的含义整体漂移。
 *       下面选的文本里，官方计数与降级估算刻意不同（例如 {@code Hello, world!} 官方 4、估算 8），
 *       所以断言失败就等于告诉我们「计量已经降级了」，而不是「数字变了」。</li>
 *   <li>DJL 默认开启 maxLength=512 截断，长文本会被量成 512，压缩阈值永远触发不了（设计文档 D2）。
 *       长文本用例的期望值是 1200，专门锁住「必须显式关闭截断」这条。</li>
 * </ol>
 */
class TokenCounterTest {

    /** 用于验证官方计数与字符估算是两套结果：68 字符 → 官方 16，按 0.6/字符估算是 41。 */
    private static final String CODE_SAMPLE = "public static void main(String[] args) { System.out.println(\"hi\"); }";

    private final TokenCounter tokenCounter = new TokenCounter();

    @Test
    void shouldReturnZeroForNullOrEmptyText() {
        assertThat(tokenCounter.count(null)).isZero();
        assertThat(tokenCounter.count("")).isZero();
    }

    @Test
    void shouldCountWithOfficialVocabularyInsteadOfCharEstimate() {
        // 13 字符：官方 4 个 token，字符估算 8 个。断言失败说明词表没加载成功、走了兜底路径。
        assertThat(tokenCounter.count("Hello, world!")).isEqualTo(4);
        // 68 字符：官方 16 个 token，字符估算 41 个。
        assertThat(tokenCounter.count(CODE_SAMPLE)).isEqualTo(16);
    }

    @Test
    void shouldSplitCjkByVocabularyNotByChar() {
        // 中文由词表决定切分：五个字符「你好，世界」是 3 个 token，不是 5 个。
        assertThat(tokenCounter.count("你好，世界")).isEqualTo(3);
        assertThat(tokenCounter.count("第 1 段：Python 是语言规范，CPython 是它的参考实现。")).isEqualTo(20);
    }

    @Test
    void shouldNotTruncateLongText() {
        String longText = "中文测试 abc 123 ".repeat(200);

        assertThat(longText).hasSize(2600);
        // 官方计数 1200：被 512 截断会得到 512，降级成字符估算会得到 1560，三种结果互不相同。
        assertThat(tokenCounter.count(longText)).isEqualTo(1200);
    }

    @Test
    void shouldReturnSameCountAcrossRepeatedCalls() {
        // 懒加载 + 串行编码：同一个实例反复调用必须是同一结果，不能把加载过程中的状态带进计量。
        assertThat(tokenCounter.count(CODE_SAMPLE)).isEqualTo(tokenCounter.count(CODE_SAMPLE));
        assertThat(tokenCounter.count(CODE_SAMPLE)).isEqualTo(16);
    }
}
