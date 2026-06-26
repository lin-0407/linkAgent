package com.link.linkagent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 多 Provider 容错配置（方案四）。
 * 把备用 Provider 列表从 application.yml 读入，由 LlmProviderManager 按优先级构建回退链。
 * 主 Provider 仍走 spring.ai.openai.* 自动配置，不经过此列表。
 */
@Component
@ConfigurationProperties(prefix = "llm.fallback")
public class LlmFallbackProperties {

    /** 是否启用回退链；默认关闭，避免未配置备用 Key 时意外请求外部服务 */
    private boolean enabled = false;

    /** Provider 限流/失败后的冷却秒数，冷却期内不重试该 Provider */
    private int cooldownSeconds = 60;

    /** 备用 Provider 列表，按配置顺序即为优先级顺序 */
    private List<ProviderConfig> providers = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(int cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    public List<ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(List<ProviderConfig> providers) {
        this.providers = providers;
    }

    /**
     * 单个备用 Provider 的配置。
     * api-key 支持 ${ENV_VAR} 占位符，由 Spring 自动解析。
     */
    public static class ProviderConfig {
        /** Provider 名称，用于日志和状态展示 */
        private String name;
        /** OpenAI 兼容 API 地址，如 https://api.openai.com */
        private String baseUrl;
        /** API Key */
        private String apiKey;
        /** 模型名称 */
        private String model;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}
