package com.link.linkagent.llm.config.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 保存/更新用户 LLM 配置的请求 DTO（P1-4）。
 * <p>
 * API Key 字段接收明文，服务端负责加密后存入 _enc 列；
 * 前端回传脱敏值时不触发更新（空字符串和 null 均视为"不修改"）。
 */
public record UserLlmConfigSaveRequest(
        /** 供应商标识：DEEPSEEK / OPENAI / SILICONFLOW / CUSTOM */
        @NotBlank(message = "供应商不能为空")
        @Size(max = 32, message = "供应商标识长度不能超过32个字符")
        String provider,

        /** LLM API 地址，为空时使用系统默认 */
        @Size(max = 512, message = "LLM API 地址长度不能超过512个字符")
        String llmBaseUrl,

        /** LLM API Key（明文），为空时不修改已有配置 */
        @Size(max = 512, message = "LLM API Key 长度不能超过512个字符")
        String llmApiKey,

        /** LLM 模型名称，为空时使用系统默认 */
        @Size(max = 128, message = "LLM 模型名称长度不能超过128个字符")
        String llmModelName,

        /** Embedding API 地址 */
        @Size(max = 512, message = "Embedding API 地址长度不能超过512个字符")
        String embeddingBaseUrl,

        /** Embedding API Key（明文），为空时不修改已有配置 */
        @Size(max = 512, message = "Embedding API Key 长度不能超过512个字符")
        String embeddingApiKey,

        /** Embedding 模型名称 */
        @Size(max = 128, message = "Embedding 模型名称长度不能超过128个字符")
        String embeddingModelName
) {}
