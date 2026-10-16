package com.link.linkagent.memory;

import java.time.LocalDateTime;

/**
 * 长期记忆记录。
 * 使用普通 JavaBean 是为了让 MyBatis 结果映射更稳定，避免 record 构造参数映射差异。
 */
public class LongTermMemoryRecord {

    private Long id;
    private String userId;
    private String memoryKey;
    private String content;
    /**
     * 重要性分数（1-100）；0 表示尚未打分。
     * 压缩时抽取的事实点由模型打分写入，步骤 8 的三维打分用它作 Importance 维度。
     */
    private Integer importance;
    private String sourceSessionId;
    private String embeddingId;
    /**
     * 上次被检索取用的时间（设计文档 D6 / E4）；null 表示从未取用。
     * 时效性维度用它而不是创建时间，否则一条每天都在用的老记忆会一直按「很旧」计算。
     * 只有上下文注入那条检索路径会写它，写入决策的召回不写（那些记忆只是被看了一眼，不算被取用）。
     */
    private LocalDateTime lastAccessedTime;
    /**
     * 记忆内容向量，JSON 数组文本（设计文档 D4 / E3）。
     * 只有 {@link LongTermMemoryMapper#listActiveForRetrieval} 会把它取出来：1024 维向量约 20KB，
     * 管理页列表不需要它，带上会让一次列表查询多传几 MB。
     */
    private String embedding;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getMemoryKey() {
        return memoryKey;
    }

    public void setMemoryKey(String memoryKey) {
        this.memoryKey = memoryKey;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getImportance() {
        return importance;
    }

    public void setImportance(Integer importance) {
        this.importance = importance;
    }

    public String getSourceSessionId() {
        return sourceSessionId;
    }

    public void setSourceSessionId(String sourceSessionId) {
        this.sourceSessionId = sourceSessionId;
    }

    public String getEmbeddingId() {
        return embeddingId;
    }

    public void setEmbeddingId(String embeddingId) {
        this.embeddingId = embeddingId;
    }

    public LocalDateTime getLastAccessedTime() {
        return lastAccessedTime;
    }

    public void setLastAccessedTime(LocalDateTime lastAccessedTime) {
        this.lastAccessedTime = lastAccessedTime;
    }

    public String getEmbedding() {
        return embedding;
    }

    public void setEmbedding(String embedding) {
        this.embedding = embedding;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
