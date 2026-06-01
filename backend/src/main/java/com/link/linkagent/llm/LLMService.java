package com.link.linkagent.llm;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
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

    private final ChatClient chatClient;
    private final LlmCallGuardProperties guardProperties;

    protected LLMService() {
        this.chatClient = null;
        this.guardProperties = new LlmCallGuardProperties();
    }

    @Autowired
    public LLMService(ChatClient.Builder builder, LlmCallGuardProperties guardProperties) {
        this.chatClient = builder.build();
        this.guardProperties = guardProperties;
    }

    LLMService(LlmCallGuardProperties guardProperties) {
        this.chatClient = null;
        this.guardProperties = guardProperties;
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
        ChatResponse chatResponse = chatClient
                .prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .chatResponse();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        return toCallResult(chatResponse, elapsedMs);
    }

    void validatePromptLength(String systemPrompt, String userMessage) {
        if (!guardProperties.isEnabled()) {
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

    private String buildSystemPrompt() {
        return """
                你是一名资深编程助手，精通 Java、Spring Boot 及主流技术栈。
                你的职责是帮助开发者解决技术问题、编写高质量代码、解释复杂概念。
                回答应准确、简洁、实用，在不确定时主动告知用户你的局限性。
                所有回答必须遵纪守法，拒绝生成恶意代码或协助非法行为。
                """;
    }
}
