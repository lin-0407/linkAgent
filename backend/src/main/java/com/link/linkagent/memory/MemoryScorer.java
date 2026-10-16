package com.link.linkagent.memory;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 长期记忆三维打分的纯计算部分（设计文档 D2 / D11 / D17.1）。
 *
 * <h3>为什么把计算单独拿出来</h3>
 * 三个维度的口径（时间衰减、分数归一、权重求和）是整套检索里最需要反复调、也最需要单测钉住的部分；
 * 它不碰数据库、不调模型，单独一个类就能把边界（未来时间、未打分、负余弦、权重为 0）逐一测清楚。
 *
 * <h3>三维必须先归一化再加权</h3>
 * 时效性是指数衰减值（0-1）、重要性是 1-100 的整数分、相关性是余弦值（-1-1），量纲不同，
 * 直接相加没有意义（D11 第 2 条）。
 * <ul>
 *   <li>时效性：{@code decay^小时数}，天然落在 0-1；</li>
 *   <li>重要性：除以 100；</li>
 *   <li>相关性：裁剪到 0-1，负值当 0。</li>
 * </ul>
 */
final class MemoryScorer {

    /**
     * 未打分（importance=0）时的兜底分。
     * 与 D17.2 的口径一致（1-100 分制下兜底 40）：旧记忆没有分数，若按 0 参与排序会被系统性排到最后，
     * 而「没打分」与「不重要」是两件事。
     */
    static final int UNSCORED_IMPORTANCE = 40;

    private MemoryScorer() {
    }

    /**
     * 时效性：按真实时间差做指数衰减（D11 第 3 条明确不能照抄源码的「按条数排名」）。
     *
     * @param now              当前时间
     * @param lastAccessedTime 上次被检索取用的时间，为空时退回创建时间
     * @param createTime       创建时间
     * @param decayPerHour     每小时衰减率（如 0.99）
     * @return 0-1 的衰减值；时间基准缺失时返回 0
     */
    static double recency(LocalDateTime now, LocalDateTime lastAccessedTime, LocalDateTime createTime,
                          double decayPerHour) {
        LocalDateTime reference = lastAccessedTime != null ? lastAccessedTime : createTime;
        if (reference == null || now == null) {
            return 0.0;
        }
        double hours = Duration.between(reference, now).toMinutes() / 60.0;
        if (hours < 0) {
            // 时钟回拨、或者库里存了未来时间：按「刚刚」处理，否则会算出大于 1 的分数压过其它维度。
            hours = 0.0;
        }
        return Math.pow(decayPerHour, hours);
    }

    /**
     * 重要性：1-100 归一到 0-1。
     * null 与 0 都按 {@link #UNSCORED_IMPORTANCE} 处理；超过 100 的脏分数直接截到 1。
     */
    static double importance(Integer importance) {
        int value = importance == null || importance <= 0 ? UNSCORED_IMPORTANCE : Math.min(100, importance);
        return value / 100.0;
    }

    /**
     * 相关性：余弦值裁剪到 0-1。
     * <p>
     * 不采用 {@code (cos + 1) / 2} 那种映射：它会给正交（完全无关）的记忆 0.5 分，
     * 相当于给所有不相关记忆白送一半权重，相关性这一维就失去了区分力。
     */
    static double relevance(double cosine) {
        if (Double.isNaN(cosine) || cosine < 0.0) {
            return 0.0;
        }
        return Math.min(1.0, cosine);
    }

    /** 三维加权求和：{@code w时效*时效 + w相关*相关 + w重要*重要}。 */
    static double weighted(double recency, double relevance, double importance,
                           MemoryRetrievalProperties properties) {
        return properties.weightRecency() * recency
                + properties.weightRelevance() * relevance
                + properties.weightImportance() * importance;
    }

    /**
     * 单条记忆的打分明细。
     * 只留一个总分无法判断「是哪一维把它抬进来的」，而调权重（D17.1 的实验组）与排查误召回都要看分项，
     * 所以日志与调用方都拿这个结构。
     */
    record ScoredMemory(LongTermMemoryRecord memory, double recency, double relevance,
                        double importance, double score) {
    }
}
