package com.link.linkagent.memory;

import java.util.List;

/**
 * 长期记忆提取任务（设计文档 C6 / C7）。
 * <p>
 * 任务进 Redis 队列后由单线程消费者处理，因此载荷必须自带全部上下文，不能依赖发起方的内存状态：
 * 请求线程只负责「攒够预算 → 入队 → 返回」，提取与会话线程彻底解耦。
 * <p>
 * 两类来源共用一条队列：
 * <ul>
 *   <li>{@code fromMessageId/toMessageId}：预算扣到零触发的增量提取，消费者按游标取这批消息跑阶段一；</li>
 *   <li>{@code facts}：压缩时已经抽好的高分事实点，跳过阶段一直接进阶段二（设计文档 5.3 / 6.1）。</li>
 * </ul>
 * 两者共用一个消费者，是因为它们写的是同一张表、要按同一个游标与预算结算，分成两条链路会出现两套推进逻辑。
 *
 * @param userId        用户标识
 * @param sessionId     来源会话（写记忆的 source_session_id 用）
 * @param fromMessageId 本次待提取的消息区间起点（含边界语义由消费者按游标复核，这里只作范围声明）
 * @param toMessageId   本次待提取的消息区间终点（预算触发时是本轮最后一条消息 id）
 * @param facts         压缩链路预先抽好的候选事实；为空表示需要消费者跑阶段一
 * @param enqueuedAt    入队时间（ISO-8601 文本，排查用，不参与业务判断）
 */
public record MemoryExtractionTask(
        String userId,
        String sessionId,
        long fromMessageId,
        long toMessageId,
        List<MemoryExtractionTaskFact> facts,
        String enqueuedAt
) {

    /**
     * 规范化载荷：任务是从 Redis 反序列化出来的，历史或异常载荷可能带 {@code facts:null}，
     * 统一收敛成空列表，让 {@link #hasFacts()} 与调用方的日志都不用再各自判空。
     */
    public MemoryExtractionTask {
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    /** 预抽候选事实：字段与 {@link SummaryFactPoint} 对齐，落库前还要过阶段二的写入决策。 */
    public record MemoryExtractionTaskFact(String memoryKey, String content, Integer importance) {
    }

    /** 预算触发：让消费者按游标取增量消息并跑阶段一。 */
    public static MemoryExtractionTask forMessageRange(String userId, String sessionId,
                                                       long fromMessageId, long toMessageId, String enqueuedAt) {
        return new MemoryExtractionTask(userId, sessionId, fromMessageId, toMessageId, List.of(), enqueuedAt);
    }

    /** 压缩触发：候选已经抽好，消费者只跑阶段二。 */
    public static MemoryExtractionTask forFacts(String userId, String sessionId,
                                                List<MemoryExtractionTaskFact> facts, String enqueuedAt) {
        return new MemoryExtractionTask(userId, sessionId, 0L, 0L, List.copyOf(facts), enqueuedAt);
    }

    /**
     * 是否需要消费者按消息范围跑阶段一。
     * <p>
     * 只判断区间是否有效，不判断游标：游标之后没有增量时消费者会空跑一次并重置预算（步骤 5 已有的语义）。
     */
    public boolean hasMessageRange() {
        return toMessageId > fromMessageId;
    }

    public boolean hasFacts() {
        return facts != null && !facts.isEmpty();
    }
}
