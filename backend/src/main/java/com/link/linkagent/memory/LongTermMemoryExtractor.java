package com.link.linkagent.memory;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
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
 * 长期记忆抽取器 —— 从用户-AI 对话轮次中识别值得持久化的信息，产出结构化记忆候选。
 *
 * <h3>在记忆架构中的位置</h3>
 * 每次 Agent 产出 Final Answer 后，
 * 会调用本抽取器。抽取器通过 LLM 判断「这轮对话是否包含值得长期记住的事实」：
 * 若有，提取为 key-content 对存入 MySQL；若无，返回 {@code Optional.empty()}，零持久化开销。
 *
 * <h3>核心设计决策</h3>
 * <ul>
 *   <li><b>保守 JSON 协议</b>：要求 LLM 返回一个带 {@code shouldRemember / memoryKey / content}
 *       字段的 JSON，而非自由文本。正则解析而非 Jackson 反序列化——因为 LLM 输出的 JSON 可能带
 *       额外解释文本或格式不完美，正则比反序列化更宽容。</li>
 *   <li><b>解析失败不抛异常</b>：正则解析失败时返回空候选（{@code isValid() == false}），
 *       外层跳过存储。这条链路属于「能做最好、不做也没事」的增强功能——不能因为抽取器的 LLM 输出
 *       格式异常就让 Agent 主流程返回 500。</li>
 *   <li><b>独立 LLM 调用</b>：抽取器在 Agent 返回 Final Answer 之后、向用户展示结果之前执行，
 *       通过 {@link com.link.linkagent.llm.usage.LlmUsageContext} 统计 Token 用量，
 *       不与 Agent 主循环共享 LLM 调用上下文。</li>
 * </ul>
 */
@Component
public class LongTermMemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryExtractor.class);

    /**
     * 匹配 {@code "shouldRemember": true/false}，大小写不敏感。
     * LLM 可能在不同大小写风格间漂移，CASE_INSENSITIVE 提高匹配成功率。
     */
    private static final Pattern SHOULD_REMEMBER_PATTERN = Pattern.compile(
            "\"shouldRemember\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);

    /**
     * 匹配 {@code "memoryKey": "..."}，提取记忆键名。
     * 正则 {@code (?:[^"\\]|\\.)*} 支持 JSON 字符串中的转义字符（如 {@code \" \\ \n} 等），
     * 避免 LLM 在 memoryKey 中意外包含转义引号时正则截断。
     */
    private static final Pattern MEMORY_KEY_PATTERN = Pattern.compile(
            "\"memoryKey\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.CASE_INSENSITIVE);

    /**
     * 匹配 {@code "content": "..."}，提取记忆内容。
     * 相比 MEMORY_KEY_PATTERN 额外启用 DOTALL 模式——
     * LLM 偶尔在 content 字段中输出含换行的长文本，
     * 需要用 DOTALL 让 {@code .} 匹配换行符，否则正则会在第一个换行处截断。
     */
    private static final Pattern CONTENT_PATTERN = Pattern.compile(
            "\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final LLMService llmService;
    private final PromptService promptService;

    public LongTermMemoryExtractor(LLMService llmService, PromptService promptService) {
        this.llmService = llmService;
        this.promptService = promptService;
    }

    /**
     * 从单轮对话中提取长期记忆候选。
     *
     * <h3>执行流程</h3>
     * <ol>
     *   <li>通过 {@link LlmUsageContext.UsageScope} 标记本次 LLM 调用的业务场景为「长期记忆抽取」，
     *       用于 Langfuse 等可观测平台按场景分类 Token 用量</li>
     *   <li>调用 LLM，传入系统提示词（long_term_memory.system）和用户提示词（long_term_memory.user），
     *       要求 LLM 判断是否值得记忆并以 JSON 格式返回</li>
     *   <li>正则解析 LLM 响应，构建 {@link LongTermMemoryCandidate}</li>
     *   <li>校验候选有效性：{@code shouldRemember == true} 且 memoryKey 和 content 均非空</li>
     * </ol>
     *
     * <h3>异常处理</h3>
     * 任何异常（LLM 调用失败、正则解析异常等）都会被静默捕获，返回 {@code Optional.empty()}。
     * 设计原因：长期记忆是增强功能，不能因为抽取失败而中断主流程。
     *
     * @param userMessage 用户本轮输入
     * @param finalAnswer Agent 本轮输出的最终答案
     * @return 有效的记忆候选；无需记忆或抽取失败时返回 {@code Optional.empty()}
     */
    public Optional<LongTermMemoryCandidate> extract(String userMessage, String finalAnswer) {
        try {
            String response;
            // UsageScope 标记本次 LLM 调用场景，用于 Langfuse 等可观测平台按场景分类追踪 Token 消耗
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("长期记忆抽取")) {
                response = llmService.chat(promptService.get("long_term_memory.system"), buildUserPrompt(userMessage, finalAnswer));
            }
            LongTermMemoryCandidate candidate = parseCandidate(response);
            if (!candidate.isValid()) {
                // LLM 判定无需记忆或正则提取失败，返回空 Optional 让外层无任何持久化开销
                return Optional.empty();
            }
            return Optional.of(candidate);
        } catch (Exception exception) {
            // 抽取失败静默处理：长期记忆是增强功能，不影响 Agent 主流程
            log.warn("长期记忆抽取失败，error={}", exception.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 从 LLM 响应中解析出记忆候选。
     *
     * 三个正则独立匹配：每个字段用各自的 Pattern 提取，即使某个字段解析失败
     * 也不影响其他字段的解析结果。最终由 {@link LongTermMemoryCandidate#isValid()}
     * 统一校验三个字段是否都有效。
     *
     * @param response LLM 原始响应文本
     * @return 解析出的记忆候选（可能无效，由调用方校验）
     */
    LongTermMemoryCandidate parseCandidate(String response) {
        boolean shouldRemember = parseBoolean(response);
        String memoryKey = parseString(response, MEMORY_KEY_PATTERN);
        String content = parseString(response, CONTENT_PATTERN);
        return new LongTermMemoryCandidate(shouldRemember, memoryKey, content);
    }

    /**
     * 构建发送给 LLM 的用户提示词。
     *
     * 使用模板渲染（long_term_memory.user）将 userMessage 和 finalAnswer 注入提示词模板，
     * 而非在代码中硬编码拼接——方便后续通过修改模板文件调整抽取策略，无需改动 Java 代码。
     *
     * @param userMessage 用户本轮输入
     * @param finalAnswer Agent 本轮输出的最终答案
     * @return 渲染后的完整用户提示词
     */
    private String buildUserPrompt(String userMessage, String finalAnswer) {
        return promptService.render("long_term_memory.user", Map.of(
                "userMessage", userMessage,
                "finalAnswer", finalAnswer
        ));
    }

    /**
     * 从 LLM 响应中提取 shouldRemember 布尔值。
     *
     * 先 regex 找 "shouldRemember": true/false，再用 Boolean.parseBoolean 转换。
     * 注意：若正则未匹配到，返回 false——即「默认不记忆」的策略。这是保守的默认值：
     * 宁可漏记一条，也不因为 LLM 输出格式异常而把垃圾数据写入长期记忆。
     *
     * @param response LLM 原始响应文本
     * @return shouldRemember 的值；未匹配到返回 false
     */
    private boolean parseBoolean(String response) {
        Matcher matcher = SHOULD_REMEMBER_PATTERN.matcher(response);
        return matcher.find() && Boolean.parseBoolean(matcher.group(1));
    }

    /**
     * 从 LLM 响应中提取指定 JSON 字段的字符串值。
     *
     * 正则未匹配到时返回空串（非 null），避免下游拼接时出现 "null" 字符串。
     * 需要通过 {@code trimToDefault} 处理是因为 LLM 输出中的 JSON 字符串值可能含首尾空白。
     *
     * @param response LLM 原始响应文本
     * @param pattern  用于匹配目标字段的正则 Pattern（如 MEMORY_KEY_PATTERN / CONTENT_PATTERN）
     * @return 提取到的字段值；未匹配到返回空串
     */
    private String parseString(String response, Pattern pattern) {
        Matcher matcher = pattern.matcher(response);
        if (!matcher.find()) {
            return "";
        }
        return TextUtil.trimToDefault(matcher.group(1), "");
    }
}
