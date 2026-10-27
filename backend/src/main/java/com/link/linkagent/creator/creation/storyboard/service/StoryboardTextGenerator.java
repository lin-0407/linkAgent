package com.link.linkagent.creator.creation.storyboard.service;

import com.link.linkagent.llm.DeepSeekThinkingProperties;
import com.link.linkagent.llm.usage.LlmApiUsageService;
import com.link.linkagent.llm.usage.LlmUsageFields;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 故事板只允许一次计费请求，原始结果必须先交给运行账本结算，再由业务层校验结构。 */
@Service
public class StoryboardTextGenerator {

    private final OpenAiChatModel singleAttemptModel;
    private final LlmApiUsageService usageService;
    private final String modelName;

    public StoryboardTextGenerator(OpenAiChatModel configuredModel, OpenAiApi configuredApi,
                                   RestClient.Builder configuredRestClient, LlmApiUsageService usageService) {
        Objects.requireNonNull(configuredModel, "文本模型不能为空");
        Objects.requireNonNull(configuredApi, "文本模型 API 不能为空");
        Objects.requireNonNull(configuredRestClient, "文本模型 HTTP 配置不能为空");
        this.usageService = Objects.requireNonNull(usageService, "模型用量服务不能为空");
        this.modelName = configuredModel.getDefaultOptions().getModel();
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("故事板需要部署已配置的文本模型");
        }
        // Apache 默认传输会在 429/503 后重发 POST，单关 Spring 重试不足以保证一次计费。
        // 与本项目正式模型一致地使用 JDK 传输，克隆保留部署入口、密钥、头和 Boot 定制器，不改全局客户端。
        OpenAiApi singleAttemptApi = configuredApi.mutate()
                .restClientBuilder(configuredRestClient.clone().requestFactory(new JdkClientHttpRequestFactory()))
                .responseErrorHandler(new DefaultResponseErrorHandler()).build();
        // 独立选项避免继承工具续调、多候选和其它输出计费参数。
        OpenAiChatOptions defaults = OpenAiChatOptions.builder().model(modelName)
                .temperature(configuredModel.getDefaultOptions().getTemperature())
                .internalToolExecutionEnabled(false).build();
        this.singleAttemptModel = configuredModel.mutate().openAiApi(singleAttemptApi).defaultOptions(defaults)
                .toolExecutionEligibilityPredicate((options, response) -> false)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build()).build();
    }

    public String modelName() {
        return modelName;
    }

    public Raw generate(String system, String input, int maxOutputTokens) {
        if (system == null || system.isBlank() || input == null || input.isBlank() || maxOutputTokens <= 0) {
            throw new IllegalArgumentException("故事板文本和输出上限不能为空");
        }
        OpenAiChatOptions options = OpenAiChatOptions.builder().model(modelName).maxTokens(maxOutputTokens)
                .N(1).internalToolExecutionEnabled(false).build();
        if (DeepSeekThinkingProperties.isDeepSeekFlashModel(modelName)) {
            options.setExtraBody(Map.of("thinking", Map.of("type", "disabled")));
        }
        long started = System.nanoTime();
        final ChatResponse response;
        try {
            response = singleAttemptModel.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(input)), options));
        } catch (RuntimeException exception) {
            // 供应商异常可能携带回显的请求头或提示词，不把原异常保存进任务日志或向上透传。
            IllegalStateException safe = new IllegalStateException("故事板文本调用失败，未自动重试");
            usageService.recordTextFailure(modelName, null, elapsed(started), safe);
            throw safe;
        }
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        String reportedModel = response == null || response.getMetadata() == null ? null : response.getMetadata().getModel();
        if (reportedModel == null || reportedModel.isBlank()) {
            reportedModel = modelName;
        }
        // SDK 的通用 Usage getter 可能把缺失的单侧 token 补成 0；记账必须区分“没返回”和真实零消耗。
        OpenAiApi.Usage nativeUsage = usage != null && usage.getNativeUsage() instanceof OpenAiApi.Usage value ? value : null;
        Integer inputTokens = nativeUsage == null ? LlmUsageFields.promptTokens(usage) : nativeUsage.promptTokens();
        Integer outputTokens = nativeUsage == null ? LlmUsageFields.completionTokens(usage) : nativeUsage.completionTokens();
        usageService.recordTextSuccess(reportedModel, inputTokens,
                outputTokens, LlmUsageFields.totalTokens(usage),
                LlmUsageFields.cachedPromptTokens(usage), null, elapsed(started));
        String content = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? null : response.getResult().getOutput().getText();
        return new Raw(content, inputTokens, outputTokens, reportedModel);
    }

    private long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    public record Raw(String content, Integer inputTokens, Integer outputTokens, String modelName) {
    }
}
