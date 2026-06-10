package com.link.linkagent.memory;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆抽取器。
 * 使用保守 JSON 协议，是为了先跑通自动记忆闭环，解析失败不影响主对话。
 */
@Component
public class LongTermMemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryExtractor.class);

    private static final Pattern SHOULD_REMEMBER_PATTERN = Pattern.compile(
            "\"shouldRemember\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEMORY_KEY_PATTERN = Pattern.compile(
            "\"memoryKey\"\\s*:\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_PATTERN = Pattern.compile(
            "\"content\"\\s*:\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    private final LLMService llmService;
    private final PromptService promptService;

    public LongTermMemoryExtractor(LLMService llmService, PromptService promptService) {
        this.llmService = llmService;
        this.promptService = promptService;
    }

    public Optional<LongTermMemoryCandidate> extract(String userMessage, String finalAnswer) {
        try {
            String response = llmService.chat(promptService.get("long_term_memory.system"), buildUserPrompt(userMessage, finalAnswer));
            LongTermMemoryCandidate candidate = parseCandidate(response);
            if (!candidate.isValid()) {
                return Optional.empty();
            }
            return Optional.of(candidate);
        } catch (Exception exception) {
            log.warn("长期记忆抽取失败，error={}", exception.getMessage());
            return Optional.empty();
        }
    }

    LongTermMemoryCandidate parseCandidate(String response) {
        boolean shouldRemember = parseBoolean(response);
        String memoryKey = parseString(response, MEMORY_KEY_PATTERN);
        String content = parseString(response, CONTENT_PATTERN);
        return new LongTermMemoryCandidate(shouldRemember, memoryKey, content);
    }

    private String buildUserPrompt(String userMessage, String finalAnswer) {
        return promptService.render("long_term_memory.user", Map.of(
                "userMessage", userMessage,
                "finalAnswer", finalAnswer
        ));
    }

    private boolean parseBoolean(String response) {
        Matcher matcher = SHOULD_REMEMBER_PATTERN.matcher(response);
        return matcher.find() && Boolean.parseBoolean(matcher.group(1));
    }

    private String parseString(String response, Pattern pattern) {
        Matcher matcher = pattern.matcher(response);
        if (!matcher.find()) {
            return "";
        }
        return TextUtil.trimToDefault(matcher.group(1), "");
    }
}
