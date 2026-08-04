package com.link.linkagent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 严格 Function Calling 的独立调用配置。
 *
 * 严格模式目前只在 DeepSeek Beta 端点提供，因此不能复用普通 ChatClient 的正式地址；
 * 单独配置可以在严格能力异常时回退现有链路，也不会影响普通对话、RAG 和自动补全。
 */
@Component
@ConfigurationProperties(prefix = "llm.strict-function-calling")
public class StrictFunctionCallingProperties {

    private boolean enabled = true;
    private String baseUrl;
    private String completionsPath = "/beta/chat/completions";
    private String apiKey;
    private String model;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCompletionsPath() {
        return completionsPath;
    }

    public void setCompletionsPath(String completionsPath) {
        this.completionsPath = completionsPath;
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

    public boolean isConfigured() {
        return enabled
                && hasText(baseUrl)
                && hasText(completionsPath)
                && hasText(apiKey)
                && hasText(model);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
