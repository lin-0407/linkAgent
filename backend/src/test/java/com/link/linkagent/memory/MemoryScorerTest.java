package com.link.linkagent.memory;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 三维打分的口径测试（设计文档 D2 / D11 / D17.1）。
 * 这套边界（未来时间、未打分、负余弦、等权对照组）是排序正确性的底线，必须钉住。
 */
class MemoryScorerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 12, 0);

    @Test
    void shouldDecayRecencyByRealElapsedHours() {
        // 关键差异：按真实时间差衰减，而不是照抄源码的「按记忆条数排名」（D11 第 3 条）
        assertThat(MemoryScorer.recency(NOW, NOW, null, 0.99)).isCloseTo(1.0, within(1e-9));
        assertThat(MemoryScorer.recency(NOW, NOW.minusHours(1), null, 0.99)).isCloseTo(0.99, within(1e-9));
        assertThat(MemoryScorer.recency(NOW, NOW.minusHours(24), null, 0.99))
                .isCloseTo(Math.pow(0.99, 24), within(1e-9));
    }

    @Test
    void shouldPreferLastAccessedTimeOverCreateTime() {
        // D6：基准是「上次被取用」，不是创建时间；否则一条天天在用的老记忆会被算成很旧
        assertThat(MemoryScorer.recency(NOW, NOW, NOW.minusDays(30), 0.99)).isCloseTo(1.0, within(1e-9));
        assertThat(MemoryScorer.recency(NOW, null, NOW.minusHours(10), 0.99))
                .isCloseTo(Math.pow(0.99, 10), within(1e-9));
    }

    @Test
    void shouldTreatFutureTimestampAsJustNow() {
        // 时钟回拨或库里存了未来时间时不能算出大于 1 的分数，否则它会压过其它两个维度
        assertThat(MemoryScorer.recency(NOW, NOW.plusHours(5), null, 0.99)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void shouldReturnZeroRecencyWithoutAnyTimeReference() {
        assertThat(MemoryScorer.recency(NOW, null, null, 0.99)).isZero();
    }

    @Test
    void shouldFallBackToUnscoredImportanceWhenScoreMissing() {
        assertThat(MemoryScorer.importance(null)).isCloseTo(0.4, within(1e-9));
        assertThat(MemoryScorer.importance(0)).isCloseTo(0.4, within(1e-9));
        assertThat(MemoryScorer.importance(100)).isCloseTo(1.0, within(1e-9));
        // 脏分数截到 100，不参与加权时放大
        assertThat(MemoryScorer.importance(150)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void shouldClampNegativeCosineToZeroInsteadOfHalfCredit() {
        // 正交（完全无关）应当是 0 分：若用 (cos+1)/2 映射，无关记忆会白拿 0.5 的权重
        assertThat(MemoryScorer.relevance(0.0)).isZero();
        assertThat(MemoryScorer.relevance(-0.4)).isZero();
        assertThat(MemoryScorer.relevance(0.7)).isCloseTo(0.7, within(1e-9));
        assertThat(MemoryScorer.relevance(Double.NaN)).isZero();
    }

    @Test
    void shouldApplySourceWeights() {
        MemoryRetrievalProperties properties = new MemoryRetrievalProperties();
        // 起步值照抄源码实际生效的 0.5 : 3 : 2（D17.1）
        assertThat(MemoryScorer.weighted(1.0, 0.5, 0.5, properties)).isCloseTo(0.5 + 1.5 + 1.0, within(1e-9));
    }

    @Test
    void shouldSupportEqualWeightExperimentGroup() {
        // D17.1 要求保留 [1, 1, 1] 作为对照实验组：改三个权重即可做同批数据的对比
        MemoryRetrievalProperties properties = new MemoryRetrievalProperties(10, 0.99, 1.0, 1.0, 1.0, 5, 10, 50);
        assertThat(MemoryScorer.weighted(0.2, 0.8, 0.4, properties)).isCloseTo(1.4, within(1e-9));
    }
}
