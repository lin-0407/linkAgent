package com.link.linkagent.llm.config.model;

import java.time.LocalDateTime;

/**
 * 用户 LLM/Embedding 配置记录（P1-4），对应 user_llm_config 表。
 * <p>
 * API Key 在数据库中只存 AES-256-GCM 密文，明文仅在加密/解密瞬间存在于内存中。
 * 前端展示时返回脱敏值（如 sk-****{后4位}），用户更新 key 时才传回明文。
 */
public class UserLlmConfigRecord {

    private Long id;
    private String configId;
    private String userId;
    private String provider;
    private String llmBaseUrl;
    private String llmApiKeyEnc;
    private String llmModelName;
    private String embeddingBaseUrl;
    private String embeddingApiKeyEnc;
    private String embeddingModelName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer isDeleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConfigId() {
        return configId;
    }

    public void setConfigId(String configId) {
        this.configId = configId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getLlmBaseUrl() {
        return llmBaseUrl;
    }

    public void setLlmBaseUrl(String llmBaseUrl) {
        this.llmBaseUrl = llmBaseUrl;
    }

    public String getLlmApiKeyEnc() {
        return llmApiKeyEnc;
    }

    public void setLlmApiKeyEnc(String llmApiKeyEnc) {
        this.llmApiKeyEnc = llmApiKeyEnc;
    }

    public String getLlmModelName() {
        return llmModelName;
    }

    public void setLlmModelName(String llmModelName) {
        this.llmModelName = llmModelName;
    }

    public String getEmbeddingBaseUrl() {
        return embeddingBaseUrl;
    }

    public void setEmbeddingBaseUrl(String embeddingBaseUrl) {
        this.embeddingBaseUrl = embeddingBaseUrl;
    }

    public String getEmbeddingApiKeyEnc() {
        return embeddingApiKeyEnc;
    }

    public void setEmbeddingApiKeyEnc(String embeddingApiKeyEnc) {
        this.embeddingApiKeyEnc = embeddingApiKeyEnc;
    }

    public String getEmbeddingModelName() {
        return embeddingModelName;
    }

    public void setEmbeddingModelName(String embeddingModelName) {
        this.embeddingModelName = embeddingModelName;
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

    public Integer getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Integer isDeleted) {
        this.isDeleted = isDeleted;
    }
}
