package com.link.linkagent.llm.config.model;

import java.time.LocalDateTime;

/**
 * 用户 LLM 配置的响应 DTO（P1-4）。
 * <p>
 * API Key 返回脱敏值（如 sk-****{后4位}），Base URL 和模型名明文返回。
 * 用户看不到完整 key，但能通过脱敏前缀和后缀确认当前绑定的是哪把 key。
 */
public record UserLlmConfigResponse(
        String configId,
        String userId,
        String provider,
        String llmBaseUrl,
        /** LLM API Key 脱敏值，如 sk-****j8x2 */
        String llmApiKeyMasked,
        String llmModelName,
        String embeddingBaseUrl,
        /** Embedding API Key 脱敏值 */
        String embeddingApiKeyMasked,
        String embeddingModelName,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {}
