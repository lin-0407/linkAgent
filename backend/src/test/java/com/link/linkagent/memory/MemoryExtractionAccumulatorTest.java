package com.link.linkagent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 累加器逻辑用内存 Map 顶上 Redis 的字符串操作。
 * <p>
 * 这里验证的是「预算怎么扣、什么时候触发、游标怎么读写」这套判断，
 * SETNX / DECRBY 本身的原子性由 Redis 保证，不重复测。
 */
class MemoryExtractionAccumulatorTest {

    private Map<String, String> redis = new HashMap<>();
    private ValueOperations<String, String> valueOperations;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = new HashMap<>();
        valueOperations = mock(ValueOperations.class);
        when(valueOperations.setIfAbsent(anyString(), anyString()))
                .thenAnswer(invocation -> redis.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null);
        when(valueOperations.decrement(anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    Long delta = invocation.getArgument(1);
                    String current = redis.get(key);
                    if (current == null || !current.matches("-?\\d+")) {
                        throw new IllegalStateException("value is not an integer");
                    }
                    long remaining = Long.parseLong(current) - delta;
                    redis.put(key, String.valueOf(remaining));
                    return remaining;
                });
        when(valueOperations.get(anyString())).thenAnswer(invocation -> redis.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            redis.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString());

        redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void shouldWaitUntilBudgetRunsOut() {
        MemoryExtractionAccumulator accumulator = new MemoryExtractionAccumulator(redisTemplate, 200);

        assertThat(accumulator.consume("default", 20)).isEqualTo(MemoryExtractionAccumulator.Decision.WAIT);
        assertThat(accumulator.consume("default", 20)).isEqualTo(MemoryExtractionAccumulator.Decision.WAIT);
        // 预算初值 200，两轮各扣 20 后应该还剩 160
        assertThat(redis.get("link-agent:memory:extract:budget:default")).isEqualTo("160");

        assertThat(accumulator.consume("default", 200)).isEqualTo(MemoryExtractionAccumulator.Decision.TRIGGER);
        assertThat(accumulator.consume("default", 20)).isEqualTo(MemoryExtractionAccumulator.Decision.TRIGGER);
    }

    @Test
    void shouldInitializeBudgetOnFirstUse() {
        MemoryExtractionAccumulator accumulator = new MemoryExtractionAccumulator(redisTemplate, 100);

        assertThat(accumulator.consume("default", 30)).isEqualTo(MemoryExtractionAccumulator.Decision.WAIT);

        // 首次使用先写初值再扣，余额必须是确定的 70 而不是「未定义 - 30」
        assertThat(redis.get("link-agent:memory:extract:budget:default")).isEqualTo("70");
    }

    @Test
    void shouldResetBudgetToInitialValueAfterSuccessfulExtraction() {
        MemoryExtractionAccumulator accumulator = new MemoryExtractionAccumulator(redisTemplate, 200);
        accumulator.consume("default", 220);
        assertThat(redis.get("link-agent:memory:extract:budget:default")).isEqualTo("-20");

        accumulator.resetBudget("default");

        assertThat(redis.get("link-agent:memory:extract:budget:default")).isEqualTo("200");
    }

    @Test
    void shouldReturnDegradedWhenRedisFails() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("connection refused"));
        MemoryExtractionAccumulator accumulator = new MemoryExtractionAccumulator(redisTemplate, 200);

        // Redis 不可用时不能让记忆链路整个炸掉，由调用方退回每轮抽取
        assertThat(accumulator.consume("default", 20)).isEqualTo(MemoryExtractionAccumulator.Decision.DEGRADED);
    }

    @Test
    void shouldReadAndAdvanceCursor() {
        MemoryExtractionAccumulator accumulator = new MemoryExtractionAccumulator(redisTemplate, 200);

        assertThat(accumulator.getCursor("default")).isZero();

        accumulator.advanceCursor("default", 42L);

        assertThat(accumulator.getCursor("default")).isEqualTo(42L);
    }

    @Test
    void shouldRejectCorruptCursorInsteadOfRestartingFromZero() {
        redis.put("link-agent:memory:extract:cursor:default", "abc");
        MemoryExtractionAccumulator accumulator = new MemoryExtractionAccumulator(redisTemplate, 200);

        // 按 0 处理会让全量历史被重新抽取，宁可抛出让调用方跳过本轮
        assertThatThrownBy(() -> accumulator.getCursor("default")).isInstanceOf(IllegalStateException.class);
    }
}
