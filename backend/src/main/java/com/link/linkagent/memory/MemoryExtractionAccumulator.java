package com.link.linkagent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 长期记忆提取的预算累加器与游标（设计文档 C2 / C3 / C4 / D17.3）。
 *
 * <h3>它解决什么问题</h3>
 * 原实现每轮对话都调一次模型抽取长期记忆，同步链路里这等于给用户每轮多加一次等待。
 * 改成递减预算模型：每轮按「这一轮的重要性分数」从预算里扣，扣到零才触发一次批量提取
 * （口径核对自 Generative Agents 源码的 {@code importance_trigger_curr}，见设计文档 D17.3）。
 *
 * <h3>两个键都在 Redis</h3>
 * <ul>
 *   <li>{@code link-agent:memory:extract:budget:用户}：预算余额。放 JVM 内存里重启就归零，
 *       多实例部署时每个实例各扣各的，触发频率会随实例数翻倍。</li>
 *   <li>{@code link-agent:memory:extract:cursor:用户}：已抽取到的最大消息 id（{@link MemoryMessage#messageId()}）。
 *       <b>只在抽取成功后推进</b>：失败就停在原地，下一轮把同一批消息重新取出来重试，
 *       不会出现「消息被跳过、长期记忆里没有」的静默丢失。</li>
 * </ul>
 *
 * <h3>Redis 不可用时怎么办</h3>
 * {@link #consume(String, int)} 不抛异常，而是返回 {@link Decision#DEGRADED}，
 * 由调用方退回「每轮抽取本轮」的旧行为。记忆是增强能力：Redis 抖动不应该让长期记忆整体停摆，
 * 但也不能在拿不到游标的情况下继续按批抽取——那会把同一批消息反复抽进长期记忆。
 */
@Component
public class MemoryExtractionAccumulator {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionAccumulator.class);

    /** 与短期记忆的 {@code link-agent:memory:short-term:} 保持同一前缀风格，便于在 Redis 里一起排查。 */
    private static final String KEY_PREFIX = "link-agent:memory:extract:";

    private static final String BUDGET_SUFFIX = "budget:";
    private static final String CURSOR_SUFFIX = "cursor:";

    /**
     * 预算初值。设计文档 9.3 的 V2 起步值：期望每 10 轮触发一次 × 平均每轮重要性 20（1-100 口径）＝ 200。
     * 它是起步值不是定值，按实际观察调整；换成真实打分后触发频率会自己跟着内容走。
     */
    private static final int DEFAULT_INITIAL_BUDGET = 200;

    /**
     * 一次预算扣减的决策。
     */
    public enum Decision {
        /** 预算还没扣到零：本轮不提取。 */
        WAIT,
        /** 预算扣到零或以下：本轮触发一次批量提取。 */
        TRIGGER,
        /** Redis 不可用：调用方退回每轮抽取本轮的旧行为。 */
        DEGRADED
    }

    private final StringRedisTemplate redisTemplate;
    private final int initialBudget;

    @Autowired
    public MemoryExtractionAccumulator(StringRedisTemplate redisTemplate,
                                       @Value("${agent.memory.extract.budget-initial:200}") int initialBudget) {
        this.redisTemplate = redisTemplate;
        // 配置误配 0 或负数会让每轮都触发提取，直接按最小值 1 兜住。
        this.initialBudget = Math.max(1, initialBudget);
    }

    /** 单测用的默认预算构造器。 */
    public MemoryExtractionAccumulator(StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_INITIAL_BUDGET);
    }

    /**
     * 按本轮的重要性分数扣减预算，并判断是否需要触发提取。
     *
     * <p>预算不存在（首次使用或 Redis 被清空）时先写入初值再扣，保证第一次调用就有确定的余额。
     * 扣减用 Redis 的 DECRBY，是原子操作：同一用户的并发请求不会各扣一份丢更新。
     *
     * @param userId 用户标识
     * @param score  本轮重要性分数（1-100，打分失败时调用方传兜底分）
     * @return 扣减后的决策；Redis 异常时返回 {@link Decision#DEGRADED}，不抛给调用方
     */
    public Decision consume(String userId, int score) {
        String key = buildKey(BUDGET_SUFFIX, userId);
        int deduction = Math.max(0, score);
        try {
            redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(initialBudget));
            Long remaining = redisTemplate.opsForValue().decrement(key, deduction);
            if (remaining == null) {
                log.warn("长期记忆预算扣减未返回余额，按 Redis 不可用处理，userId={}", userId);
                return Decision.DEGRADED;
            }
            boolean trigger = remaining <= 0;
            log.info("长期记忆预算：本轮扣 {} 分，剩余 {}，{}", deduction, remaining,
                    trigger ? "达到触发线，本次触发提取" : "未到触发线");
            return trigger ? Decision.TRIGGER : Decision.WAIT;
        } catch (Exception exception) {
            log.warn("长期记忆预算不可用（Redis 异常），本轮退回每轮抽取，userId={}, error={}",
                    userId, exception.getMessage());
            return Decision.DEGRADED;
        }
    }

    /**
     * 读取已抽取到的最大消息 id。
     *
     * @return 游标值；从未抽取过时返回 0（表示「从 id 大于 0 的消息开始」）
     * @throws RuntimeException Redis 不可用或游标值不是数字；调用方应放弃本轮提取并等待下次触发
     */
    public long getCursor(String userId) {
        String value = redisTemplate.opsForValue().get(buildKey(CURSOR_SUFFIX, userId));
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            // 脏游标比没有游标更危险：按 0 处理会让全量历史被重新抽取，这里直接抛给调用方跳过本轮。
            throw new IllegalStateException("长期记忆游标不是合法数字：" + value, exception);
        }
    }

    /**
     * 推进游标。只允许在抽取成功后调用（设计文档 C4）：失败时同样调用会把没抽取的消息跳过。
     *
     * @param messageId 本批已抽取的最后一条消息 id
     */
    public void advanceCursor(String userId, long messageId) {
        redisTemplate.opsForValue().set(buildKey(CURSOR_SUFFIX, userId), String.valueOf(messageId));
    }

    /** 提取成功后把预算重置回初值（设计文档 D17.3：失败不重置，等下次重试）。 */
    public void resetBudget(String userId) {
        redisTemplate.opsForValue().set(buildKey(BUDGET_SUFFIX, userId), String.valueOf(initialBudget));
    }

    /** 预算初值，日志与止血排查时用。 */
    public int getInitialBudget() {
        return initialBudget;
    }

    private String buildKey(String suffix, String userId) {
        return KEY_PREFIX + suffix + userId;
    }
}
