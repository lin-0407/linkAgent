package com.link.linkagent.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

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

    public ChatResponse call(String systemPrompt,
                             List<Message> messages,
                             List<OpenAiApi.FunctionTool> tools,
                             Object toolChoice,
                             boolean disableThinking) {
        if (!isEnabled()) {
            throw new IllegalStateException("严格 Function Calling 未完成配置");
        }
        OpenAiChatOptions options = thinkingOptionsFactory.optionsForModel(properties.getModel());
        if (disableThinking) {
            // Spring AI 1.1.4 不会把 AssistantMessage 中的 reasoning_content 写回下一轮请求，
            // 多轮工具调用若继续开启思考会违反 DeepSeek 协议，因此仅该场景显式关闭。
            options.setExtraBody(Map.of("thinking", Map.of("type", "disabled")));
            options.setReasoningEffort(null);
        }
        options.setTools(tools);
        options.setToolChoice(toolChoice);
        options.setInternalToolExecutionEnabled(false);
        options.setParallelToolCalls(false);
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
