package com.link.linkagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 长期记忆向量化服务（设计文档 D4 / D5）。
 *
 * <h3>它是什么、谁调用它</h3>
 * 把一段文本换成向量，并负责向量与「JSON 数组文本」之间的转换。调用方只有 {@link LongTermMemory}：
 * 写入时算一次存进 {@code t_long_term_memory.embedding}，检索时算查询向量，再在内存里算余弦相似度
 * （D5：单用户几十到几百条记忆，不引入 Milvus）。
 *
 * <h3>模型 Bean 可以为空</h3>
 * {@code spring.ai.model.embedding} 默认是 {@code none}（演示环境不创建 Embedding Bean，见 application.yml），
 * 此时 {@link #available()} 返回 false，检索退化成「时效性 + 重要性」两维。
 * 这是设计文档要求的降级姿势：向量能力缺失不能让记忆读取整体失败。
 *
 * <h3>超时为什么是 5 秒</h3>
 * 检索在请求线程上，向量化只是「让排序更准」的一步，不值得让用户等 5 分钟（那是抽取链路的量级）。
 * 写入路径同样用它：失败时向量留空，下一次读取由 {@link LongTermMemory} 的补算路径补上，
 * 不会因为一次网络抖动让某条记忆永远没有向量。
 */
@Component
public class MemoryEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingService.class);

    /** 用量统计的场景名：把这条链路的向量化开销与知识库检索区分开。 */
    static final String USAGE_SCENE = "长期记忆向量化";

    /** 单条文本上限：与记忆内容上限 2000 字一致，防止个别超长文本撑爆单条向量化输入。 */
    static final int MAX_TEXT_LENGTH = 2000;

    private static final int DEFAULT_TIMEOUT_SECONDS = 5;

    private static final int DEFAULT_BATCH_SIZE = 10;

    /** 当前配置下的向量模型；为 null 表示没有向量能力（配置关掉了）。 */
    private final EmbeddingModel embeddingModel;

    private final ObjectMapper objectMapper;

    private final int timeoutSeconds;

    private final int batchSize;

    @Autowired
    public MemoryEmbeddingService(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                  ObjectMapper objectMapper,
                                  MemoryRetrievalProperties retrievalProperties) {
        this(embeddingModelProvider.getIfAvailable(), objectMapper,
                retrievalProperties.embeddingTimeoutSeconds(), retrievalProperties.embeddingBatchSize());
    }

    /** 单测构造器：直接给模型（可以为 null，表示当前没有向量能力）。 */
    public MemoryEmbeddingService(EmbeddingModel embeddingModel, ObjectMapper objectMapper,
                                  int timeoutSeconds, int batchSize) {
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.batchSize = Math.max(1, batchSize);
    }

    /** 没有向量能力时的占位实现：可用性恒为 false，检索按两维降级，不产生任何模型调用。 */
    public static MemoryEmbeddingService disabled() {
        return new MemoryEmbeddingService(null, new ObjectMapper(), DEFAULT_TIMEOUT_SECONDS, DEFAULT_BATCH_SIZE);
    }

    /** 当前是否具备向量能力。 */
    public boolean available() {
        return embeddingModel != null;
    }

    /** 向量的维数；没有向量能力或模型未上报时返回 0，仅用于日志与排查。 */
    public int dimensions() {
        if (embeddingModel == null) {
            return 0;
        }
        try {
            return embeddingModel.dimensions();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    /**
     * 向量化一条文本。失败返回 null，调用方按「没有相关性」处理。
     *
     * @param text 待向量化文本，空白时直接返回 null（不发起调用）
     */
    public float[] embedOne(String text) {
        if (!TextUtil.hasText(text)) {
            return null;
        }
        List<float[]> vectors = embedAll(List.of(text));
        return vectors.isEmpty() ? null : vectors.get(0);
    }

    /**
     * 批量向量化。
     * <p>
     * 返回列表与入参一一对应，空白文本与失败的条目都是 null，调用方不需要关心分批细节。
     * <b>为什么分批</b>：Qwen 的 OpenAI 兼容模式单批硬上限是 10 条，超了整批报 400
     * （知识库索引侧已经踩过同一个坑，见 application.yml 里 batch-size 的注释）。
     *
     * @param texts 待向量化文本列表
     * @return 与入参等长的向量列表，缺失项为 null
     */
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> results = new ArrayList<>();
        if (texts == null || texts.isEmpty()) {
            return results;
        }
        results.addAll(Collections.nCopies(texts.size(), null));
        if (!available()) {
            return results;
        }
        // 空白文本不发给模型：整批里混进一个空串会让这一批整体报错，先把有效条目挑出来再分批。
        List<Integer> pendingIndexes = new ArrayList<>();
        List<String> pendingTexts = new ArrayList<>();
        for (int index = 0; index < texts.size(); index++) {
            String text = texts.get(index);
            if (TextUtil.hasText(text)) {
                pendingIndexes.add(index);
                pendingTexts.add(TextUtil.abbreviate(text.trim(), MAX_TEXT_LENGTH));
            }
        }
        for (int start = 0; start < pendingTexts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, pendingTexts.size());
            List<float[]> vectors = embedChunk(pendingTexts.subList(start, end));
            for (int offset = 0; offset < vectors.size(); offset++) {
                results.set(pendingIndexes.get(start + offset), vectors.get(offset));
            }
        }
        return results;
    }

    /** 一批文本的向量化：整批失败时这一批全部按 null 处理，不影响其它批次。 */
    private List<float[]> embedChunk(List<String> chunk) {
        try {
            List<float[]> vectors;
            // UsageScope 标记场景，让这次向量化在用量面板里单独归类（MemoryCallTimeout 会把上下文带到工作线程）。
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene(USAGE_SCENE)) {
                vectors = MemoryCallTimeout.call("长期记忆向量化", timeoutSeconds, TimeUnit.SECONDS,
                        () -> embeddingModel.embed(chunk));
            }
            if (vectors == null || vectors.size() != chunk.size()) {
                log.warn("长期记忆向量化返回条数不匹配，期望 {} 条，实际 {} 条，本批按无向量处理",
                        chunk.size(), vectors == null ? 0 : vectors.size());
                return new ArrayList<>(Collections.nCopies(chunk.size(), null));
            }
            return vectors;
        } catch (Exception exception) {
            // 向量化失败只影响排序精度：调用方照常返回记忆，只是相关性维度为 0。
            log.warn("长期记忆向量化失败，本批 {} 条按无向量处理，error={}", chunk.size(), exception.getMessage());
            return new ArrayList<>(Collections.nCopies(chunk.size(), null));
        }
    }

    /**
     * 向量序列化成 JSON 数组文本；向量为空时返回 null。
     * 返回 null 而不是字符串 "null"，是为了让数据库列保持 NULL，读取侧能用同一个判断识别「没有向量」。
     */
    public String toJson(float[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (Exception exception) {
            log.warn("长期记忆向量序列化失败，按无向量处理，error={}", exception.getMessage());
            return null;
        }
    }

    /**
     * JSON 数组文本解析回向量；空值或格式不对时返回 null。
     * 解析失败不抛异常：库里可能留着半截写入的文本，一条坏数据不该让整次检索失败。
     */
    public float[] parse(String json) {
        if (TextUtil.isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (Exception exception) {
            log.warn("长期记忆向量解析失败，该条按无向量处理，error={}", exception.getMessage());
            return null;
        }
    }

    /**
     * 余弦相似度。
     * <p>
     * 向量缺失、长度不一致、模长为 0 时返回 0：这几种情况都是「两个向量没法比」，
     * 返回 0 会在 {@link MemoryScorer#relevance(double)} 里被当成不相关，不会影响其它两个维度。
     */
    public static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.length; index++) {
            dot += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
