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
    private volatile long disabledUntilNanos;

    public StrictFunctionCallingClient(StrictFunctionCallingProperties properties,
                                       DeepSeekThinkingOptionsFactory thinkingOptionsFactory) {
        this.properties = properties;
        this.thinkingOptionsFactory = thinkingOptionsFactory;
    }

    public boolean isEnabled() {
        return properties.isConfigured() && System.nanoTime() >= disabledUntilNanos;
    }

    /**
     * 本次 strict 调用使用的模型是否开着思考。
     * <p>
     * 思考模式只接受 {@code tool_choice=auto}：指定具体函数和 required 都会被服务端 400 拒绝，
     * 因此 LLMService 需要据此选择请求形状（见 {@code LLMService#structuredResultToolChoice}）。
     */
    public boolean isThinkingEnabled() {
        return thinkingOptionsFactory.isThinkingEnabled(properties.getModel());
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
        if (!isEnabled()) {
            throw new IllegalStateException("严格 Function Calling 未完成配置");
        }
        OpenAiChatOptions options = buildOptions(tools, toolChoice, disableThinking, maxTokens);
        try {
            return client().prompt()
                    .system(systemPrompt == null ? "" : systemPrompt)
                    .messages(messages)
                    .options(options)
                    .call()
                    .chatResponse();
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
        OpenAiChatOptions options = thinkingOptionsFactory.optionsForModel(properties.getModel());
        if (disableThinking) {
            // Spring AI 1.1.4 不会把 AssistantMessage 中的 reasoning_content 写回下一轮请求，
            // 多轮工具调用若继续开启思考会违反 DeepSeek 协议，因此该场景显式关闭；
            // 打分调用也走这里：作者 2026-09-19 确认只对打分降级，其它记忆链路保持思考不影响质量。
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
}
