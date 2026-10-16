package com.link.linkagent.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 长期记忆检索与向量化配置（设计文档 D 组 / 实施步骤 8）。
 *
 * <h3>为什么单独一个配置类</h3>
 * 三维权重、衰减率、top-K 都是要按真实数据调的参数（D17.1 要求保留等权对照组做实验），
 * 放进 {@link LongTermMemory} 的构造参数会让「存储 + 检索」的入口同时承担调参职责。
 *
 * <h3>默认值的出处</h3>
 * 权重与衰减率照抄 Generative Agents 源码里实际生效的常量（D17.1：{@code gw = [0.5, 3, 2]}、{@code recency_decay = 0.99}），
 * 不是凭直觉设的。衰减口径按 D11 改成「按真实时间差」——源码按记忆条数排名衰减，
 * 在本项目几十到几百条的量级下会得到一个几乎不衰减的维度。
 */
@Component
public class MemoryRetrievalProperties {

    /** 一次注入上下文的记忆条数：9.3 的 V3 起步值 10（K=10 约 230 token，成本不是瓶颈）。 */
    static final int DEFAULT_TOP_K = 10;

    /** 时效性衰减率：每小时乘 0.99。 */
    static final double DEFAULT_RECENCY_DECAY_PER_HOUR = 0.99;

    /** 三维权重：相关性 3 : 重要性 2 : 时效性 0.5，照抄源码实际生效的比值。 */
    static final double DEFAULT_WEIGHT_RECENCY = 0.5;
    static final double DEFAULT_WEIGHT_RELEVANCE = 3.0;
    static final double DEFAULT_WEIGHT_IMPORTANCE = 2.0;

    /** 向量化调用超时：检索在请求线程上，不能让一次向量化把整轮回答拖住。 */
    static final int DEFAULT_EMBEDDING_TIMEOUT_SECONDS = 5;

    /** 单批向量化条数：Qwen 的 OpenAI 兼容模式单批硬上限是 10，超了整批 400。 */
    static final int DEFAULT_EMBEDDING_BATCH_SIZE = 10;

    /** 单次读取最多补算多少条缺失向量：存量记忆多时避免一次读取打出几十个向量化请求。 */
    static final int DEFAULT_EMBEDDING_BACKFILL_LIMIT = 50;

    private final int topK;
    private final double recencyDecayPerHour;
    private final double weightRecency;
    private final double weightRelevance;
    private final double weightImportance;
    private final int embeddingTimeoutSeconds;
    private final int embeddingBatchSize;
    private final int embeddingBackfillLimit;

    @Autowired
    public MemoryRetrievalProperties(
            @Value("${agent.memory.retrieval.top-k:" + DEFAULT_TOP_K + "}") int topK,
            @Value("${agent.memory.retrieval.recency-decay-per-hour:" + DEFAULT_RECENCY_DECAY_PER_HOUR + "}") double recencyDecayPerHour,
            @Value("${agent.memory.retrieval.weight-recency:" + DEFAULT_WEIGHT_RECENCY + "}") double weightRecency,
            @Value("${agent.memory.retrieval.weight-relevance:" + DEFAULT_WEIGHT_RELEVANCE + "}") double weightRelevance,
            @Value("${agent.memory.retrieval.weight-importance:" + DEFAULT_WEIGHT_IMPORTANCE + "}") double weightImportance,
            @Value("${agent.memory.retrieval.embedding-timeout-seconds:" + DEFAULT_EMBEDDING_TIMEOUT_SECONDS + "}") int embeddingTimeoutSeconds,
            @Value("${agent.memory.retrieval.embedding-batch-size:" + DEFAULT_EMBEDDING_BATCH_SIZE + "}") int embeddingBatchSize,
            @Value("${agent.memory.retrieval.embedding-backfill-limit:" + DEFAULT_EMBEDDING_BACKFILL_LIMIT + "}") int embeddingBackfillLimit) {
        this.topK = Math.max(1, topK);
        // 衰减率必须落在 (0,1)：0 会让所有旧记忆的时效性直接归零，1 则完全不衰减（等于没有这一维）。
        this.recencyDecayPerHour = recencyDecayPerHour > 0.0 && recencyDecayPerHour < 1.0
                ? recencyDecayPerHour
                : DEFAULT_RECENCY_DECAY_PER_HOUR;
        // 权重只要求非负：0 是合法的实验取值（等于关掉某一维），负数会把排序方向拧反。
        this.weightRecency = Math.max(0.0, weightRecency);
        this.weightRelevance = Math.max(0.0, weightRelevance);
        this.weightImportance = Math.max(0.0, weightImportance);
        this.embeddingTimeoutSeconds = Math.max(1, embeddingTimeoutSeconds);
        this.embeddingBatchSize = Math.max(1, embeddingBatchSize);
        this.embeddingBackfillLimit = Math.max(0, embeddingBackfillLimit);
    }

    /** 单测构造器：全部取默认值。 */
    public MemoryRetrievalProperties() {
        this(DEFAULT_TOP_K, DEFAULT_RECENCY_DECAY_PER_HOUR, DEFAULT_WEIGHT_RECENCY, DEFAULT_WEIGHT_RELEVANCE,
                DEFAULT_WEIGHT_IMPORTANCE, DEFAULT_EMBEDDING_TIMEOUT_SECONDS, DEFAULT_EMBEDDING_BATCH_SIZE,
                DEFAULT_EMBEDDING_BACKFILL_LIMIT);
    }

    public int topK() {
        return topK;
    }

    public double recencyDecayPerHour() {
        return recencyDecayPerHour;
    }

    public double weightRecency() {
        return weightRecency;
    }

    public double weightRelevance() {
        return weightRelevance;
    }

    public double weightImportance() {
        return weightImportance;
    }

    public int embeddingTimeoutSeconds() {
        return embeddingTimeoutSeconds;
    }

    public int embeddingBatchSize() {
        return embeddingBatchSize;
    }

    public int embeddingBackfillLimit() {
        return embeddingBackfillLimit;
    }
}
