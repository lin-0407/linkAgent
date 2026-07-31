package com.link.linkagent.llm;

import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.util.TextUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 统一构造 DeepSeek 思考参数。
 *
 * Spring AI 请求和备用 Provider 都经过这里判断模型，避免把 DeepSeek 专用字段发送给其它兼容模型；
 * 运行期设置从数据库读取，因此设置页修改后下一次请求即可生效，无需重启后端。
 */
@Component
public class DeepSeekThinkingOptionsFactory {

    private final DeepSeekThinkingProperties properties;
    private final RuntimeSettingService runtimeSettingService;

    public DeepSeekThinkingOptionsFactory(DeepSeekThinkingProperties properties,
                                          RuntimeSettingService runtimeSettingService) {
        this.properties = properties;
        this.runtimeSettingService = runtimeSettingService;
    }

    /**
     * 为系统默认模型构造请求级选项；不显式覆盖模型名，保留 Spring AI 的默认模型合并逻辑。
     */
    public OpenAiChatOptions optionsForDefaultModel() {
        return optionsForModel(null);
    }

    /**
     * 为指定模型构造请求级选项，modelName 非空时同时覆盖本次请求的模型名。
     */
    public OpenAiChatOptions optionsForModel(String modelName) {
        String effectiveModel = effectiveModelName(modelName);
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        String normalizedModel = TextUtil.trimToNull(modelName);
        if (normalizedModel != null) {
            builder.model(normalizedModel);
        }
        applyThinkingOptions(builder, effectiveModel);
        return builder.build();
    }

    /**
     * 为直接使用 ChatClient.Builder 的 RAG 组件应用默认请求参数。
     * clone 是为了不改变 Spring 注入的原始 Builder，避免不同组件互相污染默认配置。
     */
    public ChatClient.Builder configureDefaultBuilder(ChatClient.Builder builder) {
        ChatClient.Builder configuredBuilder = builder.clone();
        applyThinkingOptions(configuredBuilder, optionsForDefaultModel());
        return configuredBuilder;
    }

    /**
     * 为手写 OpenAI 兼容请求体追加官方思考字段，与 Spring AI 请求保持完全一致。
     */
    public void applyToRequest(ObjectNode requestBody, String modelName) {
        if (!DeepSeekThinkingProperties.isDeepSeekFlashModel(effectiveModelName(modelName))) {
            return;
        }
        boolean enabled = isEnabled();
        ObjectNode thinking = requestBody.putObject("thinking");
        thinking.put("type", enabled ? "enabled" : "disabled");
        if (enabled) {
            requestBody.put("reasoning_effort", reasoningEffort());
        }
    }

    /**
     * 返回日志需要记录的思考等级；非 Flash 或关闭思考时为空，避免把未发送的参数伪装成实际请求参数。
     */
    public String reasoningEffortForLog(String modelName) {
        if (!DeepSeekThinkingProperties.isDeepSeekFlashModel(effectiveModelName(modelName)) || !isEnabled()) {
            return null;
        }
        return reasoningEffort();
    }

    public String effectiveModelName(String modelName) {
        return TextUtil.trimToDefault(modelName, TextUtil.trimToDefault(properties.getDefaultModel(), "deepseek-chat"));
    }

    private void applyThinkingOptions(OpenAiChatOptions.Builder builder, String modelName) {
        if (!DeepSeekThinkingProperties.isDeepSeekFlashModel(modelName)) {
            return;
        }
        boolean enabled = isEnabled();
        builder.extraBody(Map.of("thinking", Map.of("type", enabled ? "enabled" : "disabled")));
        if (enabled) {
            builder.reasoningEffort(reasoningEffort());
        }
    }

    private void applyThinkingOptions(ChatClient.Builder builder, OpenAiChatOptions options) {
        if (options.getExtraBody() != null || options.getReasoningEffort() != null) {
            builder.defaultOptions(options);
        }
    }

    private boolean isEnabled() {
        return runtimeSettingService == null
                ? properties.isEnabled()
                : runtimeSettingService.isDeepSeekThinkingEnabled();
    }

    private String reasoningEffort() {
        String value = runtimeSettingService == null
                ? properties.resolvedReasoningEffort()
                : runtimeSettingService.getDeepSeekReasoningEffort();
        String normalized = DeepSeekThinkingProperties.normalizeReasoningEffort(value);
        return normalized == null ? DeepSeekThinkingProperties.DEFAULT_REASONING_EFFORT : normalized;
    }
}
