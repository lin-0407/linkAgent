package com.link.linkagent.memory;

import com.link.linkagent.llm.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    private static final String SYSTEM_PROMPT = """
            你是长期记忆抽取器，只判断本轮对话是否包含值得长期保存的用户事实或偏好。

            只保存这些内容：
            - 用户长期偏好，例如喜欢 Java、希望回答简洁、偏好中文解释
            - 用户稳定身份，例如 Java 后端学习者、正在做作品集项目
            - 项目长期信息，例如项目技术栈、长期目标、固定约束
            - 用户明确要求后续持续遵守的规则

            不保存这些内容：
            - 临时问题、一次性报错、天气时间、工具结果
            - 普通闲聊、情绪表达、短期任务进展
            - 已经明显只对当前会话有用的信息

            你必须只输出 JSON，不要输出 Markdown，不要解释。
            memoryKey 只能从下面 5 个值里选择：
            - user.preference.example_language：用户偏好的示例语言、编程语言
            - user.preference.explanation_style：用户偏好的解释方式、回答风格
            - user.profile.summary：用户身份、学习方向、职业目标
            - project.profile.summary：项目定位、技术栈、长期目标
            - project.constraint.summary：项目固定约束、后续必须遵守的规则

            格式：
            {"shouldRemember":true,"memoryKey":"user.preference.example_language","content":"用户偏好..."}
            或：
            {"shouldRemember":false,"memoryKey":"","content":""}
            """;

    private final LLMService llmService;

    public LongTermMemoryExtractor(LLMService llmService) {
        this.llmService = llmService;
    }

    public Optional<LongTermMemoryCandidate> extract(String userMessage, String finalAnswer) {
        try {
            String response = llmService.chat(SYSTEM_PROMPT, buildUserPrompt(userMessage, finalAnswer));
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
        return """
                用户消息：
                %s

                Agent最终回答：
                %s
                """.formatted(userMessage, finalAnswer);
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
        return matcher.group(1).trim();
    }
}
