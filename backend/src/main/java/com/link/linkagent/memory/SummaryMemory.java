package com.link.linkagent.memory;

import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
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

    private final SummaryMemoryProperties properties;
    private final ChatModel memorySummaryModel;
    private final PromptService promptService;
    private final Map<String, String> sessionSummaries = new ConcurrentHashMap<>();

    public SummaryMemory(SummaryMemoryProperties properties, ChatModel memorySummaryModel, PromptService promptService) {
        this.properties = properties;
        this.memorySummaryModel = memorySummaryModel;
        this.promptService = promptService;
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
        return promptService.get("summary_memory.system") + "\n\n" +
                "当前对话消息如下：\n" +
                messages.stream()
                        .map(message -> message.role() + ": " + message.content())
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("");
    }

    public void saveSummary(String sessionId, String summary) {
        if (!properties.enabled() || TextUtil.isBlank(summary)) {
            return;
        }
        sessionSummaries.put(sessionId, summary.trim());
    }

    public int getRetainedMessageCount() {
        return Math.max(0, properties.retainedMessageCount());
    }
}
