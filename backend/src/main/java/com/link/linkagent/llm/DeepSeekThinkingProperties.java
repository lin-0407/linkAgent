package com.link.linkagent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * DeepSeek Flash 思考模式配置。
 *
 * 默认开启最高思考强度，是为了让当前默认的 DeepSeek Flash 调用直接使用明确的思考参数，
 * 同时允许通过环境变量在启动时覆盖，不把 API 请求字段散落在各个调用方。
 */
@Component
@ConfigurationProperties(prefix = "deepseek.thinking")
public class DeepSeekThinkingProperties {

    public static final String DEFAULT_REASONING_EFFORT = "max";

    private boolean enabled = true;
    private String reasoningEffort = DEFAULT_REASONING_EFFORT;
    private String defaultModel = "deepseek-chat";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    /**
     * 只接受 DeepSeek Chat Completions 官方支持的思考强度，避免把 xhigh 或任意自定义值发到接口。
     */
    public static String normalizeReasoningEffort(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "low", "high", "max" -> normalized;
            default -> null;
        };
    }

    public String resolvedReasoningEffort() {
        String normalized = normalizeReasoningEffort(reasoningEffort);
        return normalized == null ? DEFAULT_REASONING_EFFORT : normalized;
    }

    /**
     * DeepSeek Flash 的版本名可能包含 v4 或网关追加的版本后缀，因此按供应商和 flash 片段判断。
     */
    public static boolean isDeepSeekFlashModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        String normalized = modelName.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("deepseek") && normalized.contains("flash");
    }
}
