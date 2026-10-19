package com.link.linkagent.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索配置的边界收敛：配置被改成 0、负数或越界值时，检索必须仍然能跑出合理排序。
 */
class MemoryRetrievalPropertiesTest {

    @Test
    void shouldRejectOutOfRangeDecayRate() {
        // 0 会让旧记忆的时效性直接归零，1 则完全不衰减（等于没有这一维），两种都不合理
        assertThat(new MemoryRetrievalProperties(10, 0.0, 1, 1, 1, 5, 10, 50).recencyDecayPerHour())
                .isEqualTo(MemoryRetrievalProperties.DEFAULT_RECENCY_DECAY_PER_HOUR);
        assertThat(new MemoryRetrievalProperties(10, 1.0, 1, 1, 1, 5, 10, 50).recencyDecayPerHour())
                .isEqualTo(MemoryRetrievalProperties.DEFAULT_RECENCY_DECAY_PER_HOUR);
    }

    @Test
    void shouldClampNegativeWeightsToZeroAndKeepTopKUsable() {
        MemoryRetrievalProperties properties = new MemoryRetrievalProperties(0, 0.99, -1, 3, 2, 0, 0, -5);

        // 负权重会把排序方向拧反，直接按 0 处理（等价于关掉这一维），而不是让检索结果反过来
        assertThat(properties.weightRecency()).isZero();
        assertThat(properties.embeddingBatchSize()).isEqualTo(1);
        assertThat(properties.embeddingTimeoutSeconds()).isEqualTo(1);
        assertThat(properties.embeddingBackfillLimit()).isZero();
        assertThat(properties.topK()).isEqualTo(1);
    }
}
