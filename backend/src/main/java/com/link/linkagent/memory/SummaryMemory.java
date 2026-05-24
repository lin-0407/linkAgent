package com.link.linkagent.memory;

import org.springframework.ai.chat.model.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 摘要记忆入口。
 * 超过触发阈值后，将短期对话压缩为摘要，供后续轮次继续拼接上下文。
 */
@Component
public class SummaryMemory {

    private static final Logger log = LoggerFactory.getLogger(SummaryMemory.class);

    private static final String SYSTEM_PROMPT = """
            你是一个摘要助手，负责将对话内容压缩成简洁的摘要，保留关键信息和上下文。
            当对话消息数量过多时，你会被触发进行摘要压缩。
            你的输出应该是对当前对话的总结，帮助后续对话理解上下文。
            """;

    private final SummaryMemoryProperties properties;
    private final ChatModel memorySummaryModel;
    private final Map<String, String> sessionSummaries = new ConcurrentHashMap<>();

    public SummaryMemory(SummaryMemoryProperties properties, ChatModel memorySummaryModel) {
        this.properties = properties;
        this.memorySummaryModel = memorySummaryModel;
    }

    public String getSummary(String sessionId) {
        if (!properties.enabled()) {
            return "";
        }
        return sessionSummaries.getOrDefault(sessionId, "");
    }

    public boolean shouldSummarize(String sessionId, List<MemoryMessage> messages) {
        if (!properties.enabled() || messages.size() <= properties.triggerMessageCount()) {
            return false;
        }
        try {
            String prompt = buildPrompt(messages);
            String newMemory = memorySummaryModel.call(prompt);
            saveSummary(sessionId, newMemory);
            return true;
        } catch (Exception e) {
            log.error("摘要记忆压缩失败，sessionId={}, error={}", sessionId, e.getMessage());
            return false;
        }
    }

    private String buildPrompt(List<MemoryMessage> messages) {
        return SYSTEM_PROMPT + "\n\n" +
                "当前对话消息如下：\n" +
                messages.stream()
                        .map(message -> message.role() + ": " + message.content())
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("");
    }

    public void saveSummary(String sessionId, String summary) {
        if (!properties.enabled() || summary == null || summary.isBlank()) {
            return;
        }
        sessionSummaries.put(sessionId, summary.trim());
    }

    public int getRetainedMessageCount() {
        return Math.max(0, properties.retainedMessageCount());
    }
}
