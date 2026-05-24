package com.link.linkagent.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * LLM 调用服务层。
 * <p>
 * 职责：封装 Spring AI ChatClient，提供最简同步聊天能力。
 * 当前阶段（0.5）仅做 Lean 连通验证，不做多步规划、工具调用、记忆。
 */
@Service
public class LLMService {

    private final ChatClient chatClient;

    protected LLMService() {
        this.chatClient = null;
    }

    public LLMService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public String chat(String userMessage) {
        return chatClient
                .prompt()
                .system(buildSystemPrompt())
                .user(userMessage)
                .call()
                .content();
    }

    /**
     * ReAct 专用重载：接受外部构建的 system prompt。
     */
    public String chat(String systemPrompt, String userMessage) {
        return chatClient
                .prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();
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
