package com.link.linkagent.llm;

import com.link.linkagent.llm.usage.LlmApiUsageService;
import com.link.linkagent.settings.service.RuntimeSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.TimeUnit;

/**
 * LLM 调用服务层。
 * <p>
 * 职责：封装 Spring AI ChatClient，提供最简同步聊天能力。
 * 当前阶段保留统一调用入口，方便创作者工作台在这里做演示环境成本保护。
 */
@Service
public class LLMService {

    private static final Logger log = LoggerFactory.getLogger(LLMService.class);

    /** 结构化解析失败的最大尝试次数：JSON 偶发漂移时重试，超过则抛出交调用方兜底。先用常量，必要时再外置。 */
    private static final int STRUCTURED_MAX_ATTEMPTS = 3;

    private final ChatClient chatClient;
    private final LlmCallGuardProperties guardProperties;
    private final RuntimeSettingService runtimeSettingService;
    private final LlmApiUsageService llmApiUsageService;

    protected LLMService() {
        this.chatClient = null;
        this.guardProperties = new LlmCallGuardProperties();
        this.runtimeSettingService = null;
        this.llmApiUsageService = null;
    }

    @Autowired
    public LLMService(ChatClient.Builder builder,
                      LlmCallGuardProperties guardProperties,
                      RuntimeSettingService runtimeSettingService,
                      LlmApiUsageService llmApiUsageService) {
        this.chatClient = builder.build();
        this.guardProperties = guardProperties;
        this.runtimeSettingService = runtimeSettingService;
        this.llmApiUsageService = llmApiUsageService;
    }

    LLMService(LlmCallGuardProperties guardProperties) {
        this.chatClient = null;
        this.guardProperties = guardProperties;
        this.runtimeSettingService = null;
        this.llmApiUsageService = null;
    }

    public String chat(String userMessage) {
        String systemPrompt = buildSystemPrompt();
        return chatWithUsage(systemPrompt, userMessage).content();
    }

    /**
     * ReAct 专用重载：接受外部构建的 system prompt。
     */
    public String chat(String systemPrompt, String userMessage) {
        return chatWithUsage(systemPrompt, userMessage).content();
    }

    public LlmCallResult chatWithUsage(String userMessage) {
        String systemPrompt = buildSystemPrompt();
        return chatWithUsage(systemPrompt, userMessage);
    }

    /**
     * 返回内容和模型 usage。
     * 保留旧的 chat 方法，是为了不强迫所有调用方一次性改造；新增方法先服务评测和成本统计。
     */
    public LlmCallResult chatWithUsage(String systemPrompt, String userMessage) {
        validatePromptLength(systemPrompt, userMessage);
        long startNanos = System.nanoTime();
        try {
            ChatResponse chatResponse = chatClient
                    .prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .call()
                    .chatResponse();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            LlmCallResult result = toCallResult(chatResponse, elapsedMs);
            recordTextSuccess(result);
            return result;
        } catch (RuntimeException exception) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            recordTextFailure(elapsedMs, exception);
            throw exception;
        }
    }

    /**
     * 结构化对话：让 LLM 产出受目标类型 schema 约束的强类型对象，替代「提示词哄 JSON + 正则/字符串截取」（阶段 5.4）。
     * <p>
     * 确定性来自两层叠加：① {@code response_format=json_object} 由 DeepSeek 在 API 级保证返回合法 JSON 语法
     *（也满足其「prompt 必须含 json」的硬性要求，因为 {@code .entity} 会把 schema 指令写进 prompt）；
     * ② {@code .entity(type)} 内部用 {@code BeanOutputConverter} 依据目标类型生成 schema 指令并解析为强类型。
     * json_object 只保证语法、不保证字段，故解析失败时重试 {@link #STRUCTURED_MAX_ATTEMPTS} 次。
     * <p>
     * 为什么放在 LLMService：结构化输出属于「调模型」能力，归 LLM 服务层最自然，并复用这里的成本 guard
     *（{@link #validatePromptLength}），不让上层各自持有 ChatClient 选项细节。泛型以便 ReAct 步与业务 JSON 复用同一出口。
     *
     * @param type 目标类型（如 {@link com.link.linkagent.core.ReActStep} 或业务建议 record）
     * @return 解析后的强类型对象
     * @throws RuntimeException 连续重试仍解析失败时抛出最后一次异常，由调用方按场景兜底
     */
    public <T> T chatStructured(String systemPrompt, String userMessage, Class<T> type) {
        validatePromptLength(systemPrompt, userMessage);
        // 仅设 responseFormat；model 等默认项由 Spring AI 在模型层合并保留（见 5.4 文档 §9 运行期待确认）
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null))
                .build();
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= STRUCTURED_MAX_ATTEMPTS; attempt++) {
            long startNanos = System.nanoTime();
            try {
                T result = chatClient
                        .prompt()
                        .system(systemPrompt)
                        .user(userMessage)
                        .options(options)
                        .call()
                        .entity(type);
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                // entity(...) 不暴露 ChatResponse usage；这里仍记录一次真实调用和耗时，token 保持未知，避免伪造成本数据。
                recordStructuredTextSuccess(elapsedMs);
                return result;
            } catch (RuntimeException ex) {
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                recordTextFailure(elapsedMs, ex);
                // json_object 只保证语法、不保证字段，偶发字段不符或空内容时重试；保留最后一次异常向上抛
                lastError = ex;
                log.warn("结构化输出第 {}/{} 次解析失败：{}", attempt, STRUCTURED_MAX_ATTEMPTS, ex.getMessage());
            }
        }
        throw lastError;
    }

    void validatePromptLength(String systemPrompt, String userMessage) {
        if (!isGuardEnabled()) {
            return;
        }
        // 关闭保护必须显式设置 enabled=false，避免字符上限误配成 0 时反而绕过成本保护。
        int maxPromptChars = Math.max(1, guardProperties.getMaxPromptChars());
        int promptChars = safeLength(systemPrompt) + safeLength(userMessage);
        if (promptChars <= maxPromptChars) {
            return;
        }
        // 在调用模型前短路，是为了把超限问题控制在业务层，避免已经产生模型请求后才失败。
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "本次 AI 分析输入过长，当前限制为 " + maxPromptChars + " 个字符，请先精简文稿、评论或弹幕样例后重试。"
        );
    }

    private boolean isGuardEnabled() {
        // 测试构造器不注入设置服务时，回退原配置值，避免单元测试必须感知设置模块。
        return runtimeSettingService == null
                ? guardProperties.isEnabled()
                : runtimeSettingService.isLlmGuardEnabled();
    }

    private int safeLength(String text) {
        return text == null ? 0 : text.length();
    }

    private LlmCallResult toCallResult(ChatResponse chatResponse, long elapsedMs) {
        ChatResponseMetadata metadata = chatResponse == null ? null : chatResponse.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        // 不同模型供应商返回 usage 的完整度不一致；缺失时保留 null，比伪造 0 更利于后续排查统计口径。
        return new LlmCallResult(
                extractContent(chatResponse),
                metadata == null ? null : trimToNull(metadata.getModel()),
                extractPromptTokens(usage),
                extractCompletionTokens(usage),
                extractTotalTokens(usage),
                elapsedMs
        );
    }

    Integer extractPromptTokens(Usage usage) {
        return isMissingUsage(usage) ? null : usage.getPromptTokens();
    }

    Integer extractCompletionTokens(Usage usage) {
        return isMissingUsage(usage) ? null : usage.getCompletionTokens();
    }

    Integer extractTotalTokens(Usage usage) {
        return isMissingUsage(usage) ? null : usage.getTotalTokens();
    }

    private boolean isMissingUsage(Usage usage) {
        if (usage == null) {
            return true;
        }
        // Spring AI 的 EmptyUsage 会把未知 usage 表达成 0；这里转回 null，避免把“供应商未返回”误统计成“真实消耗为 0”。
        return isZeroOrNull(usage.getPromptTokens())
                && isZeroOrNull(usage.getCompletionTokens())
                && isZeroOrNull(usage.getTotalTokens());
    }

    private boolean isZeroOrNull(Integer value) {
        return value == null || value == 0;
    }

    private String extractContent(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
            return "";
        }
        return chatResponse.getResult().getOutput().getText();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void recordTextSuccess(LlmCallResult result) {
        if (llmApiUsageService == null || result == null) {
            return;
        }
        llmApiUsageService.recordTextSuccess(
                result.modelName(),
                result.promptTokens(),
                result.completionTokens(),
                result.totalTokens(),
                result.elapsedMs()
        );
    }

    private void recordStructuredTextSuccess(long elapsedMs) {
        if (llmApiUsageService == null) {
            return;
        }
        llmApiUsageService.recordTextSuccess(null, null, null, null, elapsedMs);
    }

    private void recordTextFailure(long elapsedMs, RuntimeException exception) {
        if (llmApiUsageService == null) {
            return;
        }
        llmApiUsageService.recordTextFailure(elapsedMs, exception);
    }

    private String buildSystemPrompt() {
        return """
                你是一名资深编程助手，精通 Java、Spring Boot 及主流技术栈。
                你的职责是帮助开发者解决技术问题、编写高质量代码、解释复杂概念。
                回答应准确、简洁、实用，在不确定时主动告知用户你的局限性。
                所有回答必须遵纪守法，拒绝生成恶意代码或协助非法行为。
                """;
    }
}
