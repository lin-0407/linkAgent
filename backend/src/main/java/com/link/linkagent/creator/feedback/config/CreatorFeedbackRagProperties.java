package com.link.linkagent.creator.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 创作者反馈追问 RAG 业务配置。
 * <p>
 * 这里的开关独立于 spring.ai 基础设施开关：spring.ai.vectorstore.type / spring.ai.model.embedding 只决定
 * Spring AI 是否创建 Milvus 和 Embedding Bean；本类的 {@link #enabled} 才决定反馈追问业务是否真正走向量检索。
 * 两层分开，是为了避免“基础设施连上了但业务还没准备好”时追问链路被动切到 RAG。
 */
@Component
@ConfigurationProperties(prefix = "creator.feedback.rag")
public class CreatorFeedbackRagProperties {

    /**
     * 默认关闭。演示环境即使忘记配置，也不会触发 Embedding 调用和 Milvus 连接。
     */
    private boolean enabled = false;

    /**
     * 单次向量检索返回的候选证据上限。
     */
    private int topK = 8;

    /**
     * 向量命中数低于该值时合并 SQL 证据兜底，避免召回过少导致回答缺证据。
     */
    private int minVectorHitCount = 3;

    /**
     * 单次重建索引最多写入的明细条数。默认 300，是为了保护演示环境 Embedding 成本；接口层另有 1000 的硬上限。
     */
    private int maxIndexItems = 300;

    /**
     * 重建索引时是否默认包含噪声明细。默认不包含，避免把“哈哈哈”等无意义内容也写进向量库浪费 Embedding。
     */
    private boolean includeNoiseDefault = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getMinVectorHitCount() {
        return minVectorHitCount;
    }

    public void setMinVectorHitCount(int minVectorHitCount) {
        this.minVectorHitCount = minVectorHitCount;
    }

    public int getMaxIndexItems() {
        return maxIndexItems;
    }

    public void setMaxIndexItems(int maxIndexItems) {
        this.maxIndexItems = maxIndexItems;
    }

    public boolean isIncludeNoiseDefault() {
        return includeNoiseDefault;
    }

    public void setIncludeNoiseDefault(boolean includeNoiseDefault) {
        this.includeNoiseDefault = includeNoiseDefault;
    }
}
