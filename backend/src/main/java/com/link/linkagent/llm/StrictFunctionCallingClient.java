package com.link.linkagent.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 只访问 DeepSeek Beta strict 端点的客户端。
 *
 * 该客户端只负责发送原生 Function Calling 请求，不自动执行函数；工具白名单、超时、重试和审计
 * 继续由项目自己的 AgentExecutor 与 ToolExecutor 控制，避免框架内部循环绕过现有安全边界。
 */
@Component
public class StrictFunctionCallingClient {

    private static final long FAILURE_COOLDOWN_NANOS = 60_000_000_000L;

    private final StrictFunctionCallingProperties properties;
    private final DeepSeekThinkingOptionsFactory thinkingOptionsFactory;
    private volatile ChatClient chatClient;
    private volatile ChatClient multiAgentClient;
    @org.springframework.beans.factory.annotation.Value("${agent.multi.worker-timeout-seconds:120}")
    private long multiAgentTimeoutSeconds = 120;
    private volatile long disabledUntilNanos;
    private RuntimeTextModelClient runtimeTextModel;

    @org.springframework.beans.factory.annotation.Autowired
    public void configureRuntimeTextModel(RuntimeTextModelClient runtimeTextModel) {
        this.runtimeTextModel = runtimeTextModel;
    }

    public StrictFunctionCallingClient(StrictFunctionCallingProperties properties,
                                       DeepSeekThinkingOptionsFactory thinkingOptionsFactory) {
        this.properties = properties;
        this.thinkingOptionsFactory = thinkingOptionsFactory;
    }

    public boolean isEnabled() {
        if (runtimeTextModel != null) {
            var selected = runtimeTextModel.active();
            if (selected.isPresent()) return properties.isEnabled() && selected.get().supportsStrict()
                    && System.nanoTime() >= disabledUntilNanos;
        }
        return properties.isConfigured() && System.nanoTime() >= disabledUntilNanos;
    }

    /**
     * 本次 strict 调用使用的模型是否开着思考。
     * <p>
     * 思考模式只接受 {@code tool_choice=auto}：指定具体函数和 required 都会被服务端 400 拒绝，
     * 因此 LLMService 需要据此选择请求形状（见 {@code LLMService#structuredResultToolChoice}）。
     */
    public boolean isThinkingEnabled() {
        return thinkingOptionsFactory.isThinkingEnabled(currentModelName());
    }

    public ChatResponse call(String systemPrompt,
                             List<Message> messages,
                             List<OpenAiApi.FunctionTool> tools,
                             Object toolChoice,
                             boolean disableThinking) {
        return call(systemPrompt, messages, tools, toolChoice, disableThinking, null);
    }

    /**
     * 带输出上限的重载。
     *
     * @param maxTokens 单次输出上限；null 表示不限制。打分这类轻量判断用它，避免思考跑飞写出几万 token
     */
    public ChatResponse call(String systemPrompt,
                             List<Message> messages,
                             List<OpenAiApi.FunctionTool> tools,
                             Object toolChoice,
                             boolean disableThinking,
                             Integer maxTokens) {
        com.link.linkagent.core.multi.MultiAgentExecutionContext.check();
        if (!isEnabled()) {
            throw new IllegalStateException("严格 Function Calling 未完成配置");
        }
        OpenAiChatOptions options = buildOptions(tools, toolChoice, disableThinking, maxTokens);
        try {
            ChatResponse response = client().prompt()
                    .system(systemPrompt == null ? "" : systemPrompt)
                    .messages(messages)
                    .options(options)
                    .call()
                    .chatResponse();
            // 记录真实请求形状与消息：下一轮摘要压缩要原样重放它（tools/thinking/tool_choice/消息任何一处不同，
            // 供应商端的 prompt 前缀都会在正文之前分叉，25 万 token 就命中不了缓存）。
            ChatReplayContext.record(ChatRequestEnvelope.strict(systemPrompt, messages, tools, toolChoice,
                    disableThinking, maxTokens));
            return response;
        } catch (RuntimeException exception) {
            // 端点或 Provider 不支持 strict 时进入短暂冷却，让同一请求的旧链路回退不再重复撞 Beta。
            disabledUntilNanos = System.nanoTime() + FAILURE_COOLDOWN_NANOS;
            throw exception;
        }
    }

    /**
     * 组装 strict 请求选项。
     * <p>
     * 抽成独立方法是为了能单测「关思考 + 输出上限」这两个开关真的写进了请求选项——
     * 直接断言 options 比 mock 整条 ChatClient 调用链可靠得多。
     */
    OpenAiChatOptions buildOptions(List<OpenAiApi.FunctionTool> tools,
                                   Object toolChoice,
                                   boolean disableThinking,
                                   Integer maxTokens) {
        OpenAiChatOptions options = thinkingOptionsFactory.optionsForModel(currentModelName());
        if (disableThinking) {
            // Spring AI 1.1.4 不会把 AssistantMessage 中的 reasoning_content 写回下一轮请求，
            // 多轮工具调用若继续开启思考会违反 DeepSeek 协议，因此该场景显式关闭；
            // 是否关闭与强度分开处理：记忆打分现在开启 low，不再经过这里的关闭分支。
            options.setExtraBody(Map.of("thinking", Map.of("type", "disabled")));
            options.setReasoningEffort(null);
        }
        if (maxTokens != null) {
            options.setMaxTokens(maxTokens);
        }
        options.setTools(tools);
        options.setToolChoice(toolChoice);
        options.setInternalToolExecutionEnabled(false);
        options.setParallelToolCalls(false);
        return options;
    }

    private ChatClient client() {
        if (runtimeTextModel != null) {
            var selected = runtimeTextModel.client(false,
                    com.link.linkagent.core.multi.MultiAgentExecutionContext.active(), true);
            if (selected.isPresent()) return selected.get();
        }
        if (com.link.linkagent.core.multi.MultiAgentExecutionContext.active()) return multiAgentClient();
        ChatClient current = chatClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (chatClient == null) {
                // completionsPath 单独指向 /beta/chat/completions，避免把全局普通调用一起切到 Beta。
                OpenAiApi api = OpenAiApi.builder()
                        .baseUrl(properties.getBaseUrl())
                        .completionsPath(properties.getCompletionsPath())
                        .apiKey(properties.getApiKey())
                        // 独立 builder 不经过 Boot 的定制器，也必须完整读取模型分块响应。
                        .restClientBuilder(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()))
                        .build();
                OpenAiChatModel model = OpenAiChatModel.builder()
                        .openAiApi(api)
                        .defaultOptions(thinkingOptionsFactory.optionsForModel(properties.getModel()))
                        .build();
                chatClient = ChatClient.create(model);
            }
            return chatClient;
        }
    }

    private String currentModelName() {
        return runtimeTextModel == null ? properties.getModel()
                : runtimeTextModel.active().map(RuntimeTextModelClient.TextConfig::modelName).orElse(properties.getModel());
    }

    private ChatClient multiAgentClient() {
        ChatClient current = multiAgentClient;
        if (current != null) return current;
        synchronized (this) {
            if (multiAgentClient == null) {
                // 多 Agent 请求可能在服务端已执行，严格输出也只允许发送一次。
                var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
                var factory = new JdkClientHttpRequestFactory(http);
                factory.setReadTimeout(java.time.Duration.ofSeconds(Math.max(1, multiAgentTimeoutSeconds)));
                OpenAiApi api = OpenAiApi.builder().baseUrl(properties.getBaseUrl())
                        .completionsPath(properties.getCompletionsPath()).apiKey(properties.getApiKey())
                        .restClientBuilder(RestClient.builder().requestFactory(factory))
                        .responseErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler()).build();
                OpenAiChatModel model = OpenAiChatModel.builder().openAiApi(api)
                        .defaultOptions(thinkingOptionsFactory.optionsForModel(properties.getModel()))
                        .retryTemplate(org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).noBackoff().build())
                        .build();
                multiAgentClient = ChatClient.create(model);
            }
            return multiAgentClient;
        }
    }
}
