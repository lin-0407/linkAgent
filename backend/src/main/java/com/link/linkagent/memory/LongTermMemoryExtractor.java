package com.link.linkagent.memory;

import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆抽取器 —— 从一批对话消息里识别值得持久化的信息，产出结构化记忆候选（设计文档 C12 阶段一）。
 *
 * <h3>在记忆架构中的位置</h3>
 * 由 {@link MemoryExtractionWorker} 取出队列任务后调用，不在请求线程里执行（设计文档 C6 / D9）：
 * 预算扣到零时只入队，真正跑模型的是单线程消费者。
 * 批量喂入还有一个附带收益：多轮消息一起看，代词与跨轮指代有足够上下文可以消解，抽取质量高于逐轮喂单条。
 *
 * <h3>核心设计决策</h3>
 * <ul>
 *   <li><b>一次多条候选</b>：单条契约会让同一批里的其它事实随游标一次性推过而永久错过
 *       （作者 2026-09-19 上手测试发现），因此阶段一改成输出 {@code memories} 数组，
 *       画像类同一 memoryKey 只留最高分；不同事件可同类并存，最多 {@code agent.memory.extract.max-candidates} 条。</li>
 *   <li><b>保守 JSON 协议</b>：要求 LLM 返回带 {@code shouldRemember / memories} 的 JSON，
 *       用正则逐条解析而非整体反序列化——LLM 输出常夹带解释文本，正则比反序列化更宽容。</li>
 *   <li><b>失败不吞</b>：调用失败或输出不符合契约时返回 {@link LongTermMemoryExtractionResult#failed()}，
 *       由调用方决定「游标不推进、下轮重试」。长期记忆是增强功能，失败不向上抛异常影响对话。</li>
 *   <li><b>独立 LLM 调用</b>：通过 {@link com.link.linkagent.llm.usage.LlmUsageContext} 统计 Token 用量，
 *       不与 Agent 主循环共享调用上下文。</li>
 *   <li><b>调用超时</b>：走 {@link MemoryCallTimeout} 的 5 分钟兜底（设计文档 C10），
 *       单个卡住的调用不会拖死整条队列。</li>
 * </ul>
 */
@Component
public class LongTermMemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryExtractor.class);

    /** 默认一次最多产出几条候选：超过这个数，落库前的写入决策也很难一次判断清楚。 */
    static final int DEFAULT_MAX_CANDIDATES = 5;

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
     * LLM 偶尔在 content 字段中输出含换行的长文本，需要用 DOTALL 让 {@code .} 匹配换行符。
     */
    private static final Pattern CONTENT_PATTERN = Pattern.compile(
            "\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * 匹配 {@code "importance": 85}，提取写入长期记忆的重要性分数。
     * 分数与 {@link SummaryFactPoint} 用同一套口径（1-100，设计文档 D17.2 / D3），
     * 步骤 8 的三维打分直接用它，不另外再算一遍。
     */
    private static final Pattern IMPORTANCE_PATTERN = Pattern.compile(
            "\"importance\"\\s*:\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE);

    /** 模型没给分数时的兜底值：与 D17.2 一致，按 1-100 口径取 40。 */
    static final int DEFAULT_IMPORTANCE = 40;

    private static final int MIN_IMPORTANCE = 1;
    private static final int MAX_IMPORTANCE = 100;

    private final LLMService llmService;
    private final PromptService promptService;
    private final int maxCandidates;

    /** 单测用构造器：候选上限取默认值。 */
    public LongTermMemoryExtractor(LLMService llmService, PromptService promptService) {
        this(llmService, promptService, DEFAULT_MAX_CANDIDATES);
    }

    /** 生产构造器：候选上限来自配置，便于不重新发版就调整一次抽取的产出量。 */
    @Autowired
    public LongTermMemoryExtractor(LLMService llmService, PromptService promptService,
                                   @Value("${agent.memory.extract.max-candidates:" + DEFAULT_MAX_CANDIDATES + "}") int maxCandidates) {
        this.llmService = llmService;
        this.promptService = promptService;
        this.maxCandidates = Math.max(1, maxCandidates);
    }

    /** 从一批对话消息里抽取长期记忆候选（观察日期取今天）。 */
    public LongTermMemoryExtractionResult extract(List<MemoryMessage> messages) {
        return extract(messages, LocalDate.now());
    }

    /**
     * 从一批对话消息里抽取长期记忆候选。
     *
     * @param messages        本次要抽取的消息（按时间顺序；可以是多轮，也可以只有一轮）
     * @param observationDate 观察日期：提示词据此把「昨天」这类相对时间换算成绝对日期（设计文档 C16）
     * @return 抽取结果；调用失败或输出不符合契约时 {@code succeeded()} 为 false，由调用方保持游标不动
     */
    public LongTermMemoryExtractionResult extract(List<MemoryMessage> messages, LocalDate observationDate) {
        if (messages == null || messages.isEmpty()) {
            // 没有消息可抽算不上失败：调用方按当前游标继续走，不会被这次空调用卡住。
            return LongTermMemoryExtractionResult.noMemory();
        }
        String systemPrompt;
        String userPrompt;
        try {
            systemPrompt = promptService.get("long_term_memory.system");
            userPrompt = promptService.render("long_term_memory.user", Map.of(
                    "messages", formatMessages(messages),
                    "observationDate", observationDate == null
                            ? LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                            : observationDate.format(DateTimeFormatter.ISO_LOCAL_DATE)));
        } catch (RuntimeException promptException) {
            // 提示词种子缺失是部署错误（必须重新执行 init.sql），不会自愈，单独打 ERROR 便于当场发现。
            log.error("长期记忆抽取提示词缺失或渲染失败，请确认已重新执行 init.sql 灌入 long_term_memory 种子，error={}",
                    promptException.getMessage());
            return LongTermMemoryExtractionResult.failed();
        }
        try {
            String response;
            // UsageScope 标记本次 LLM 调用场景，用于按场景分类追踪 Token 消耗
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("长期记忆抽取")) {
                // 5 分钟兜底（C10）：提取是攒批触发的，一次卡住会让游标一直停在这里。
                response = MemoryCallTimeout.call("长期记忆抽取",
                        () -> llmService.chat(systemPrompt, userPrompt));
            }
            LongTermMemoryExtractionResult result = parseResult(response);
            if (MemoryObservationContext.isTracing()) {
                MemoryObservationContext.trace("extraction", Map.of("messages", formatMessages(messages),
                        "response", response, "succeeded", result.succeeded(), "candidates", result.candidates()));
            }
            return result;
        } catch (Exception exception) {
            // 抽取失败静默处理：长期记忆是增强功能，不影响 Agent 主流程；但游标必须保持不动，下轮重试。
            log.warn("长期记忆抽取失败，游标保持不动，error={}", exception.getMessage());
            return LongTermMemoryExtractionResult.failed();
        }
    }

    /**
     * 解析 LLM 响应。
     *
     * <p>{@code shouldRemember} 匹配不到说明模型没按契约输出（提示词漂移、输出被截断、返回了解释文字），
     * 按失败处理而不是「无需记忆」——否则这批消息会因为一次坏输出被永久跳过。
     * {@code shouldRemember=true} 但一条可用候选都解析不出来，同样是失败：宁可下轮重抽，也不静默丢事实。
     *
     * @param response LLM 原始响应文本
     * @return 抽取结果；{@code shouldRemember=false} 是成功但无记忆，字段缺失是失败
     */
    LongTermMemoryExtractionResult parseResult(String response) {
        Matcher matcher = SHOULD_REMEMBER_PATTERN.matcher(response);
        if (!matcher.find()) {
            return LongTermMemoryExtractionResult.failed();
        }
        if (!Boolean.parseBoolean(matcher.group(1))) {
            return LongTermMemoryExtractionResult.noMemory();
        }
        List<LongTermMemoryCandidate> parsed = new ArrayList<>();
        Matcher keyMatcher = MEMORY_KEY_PATTERN.matcher(response);
        while (keyMatcher.find()) {
            // 截到候选自身所在的对象：字段顺序由模型决定，所以从 memoryKey 往前找 '{'、往后找 '}'，
            // 不能只从 memoryKey 往后截，否则 content 写在 memoryKey 之前时会被漏掉。
            String slice = candidateSlice(response, keyMatcher.start());
            String memoryKey = TextUtil.trimToDefault(keyMatcher.group(1), "");
            String content = parseString(slice, CONTENT_PATTERN);
            if (!TextUtil.hasText(memoryKey) || !TextUtil.hasText(content)) {
                // 单条候选不完整只跳过这一条：同一批里其它完整候选仍然有效，不能因为一条坏输出整批失败。
                log.warn("长期记忆候选字段不完整，已跳过该条：memoryKey 长度={}, content 长度={}",
                        memoryKey.length(), content.length());
                continue;
            }
            // 分数在这里就归一化：越界分数会直接进入步骤 8 的三维排序，不能原样落库。
            parsed.add(new LongTermMemoryCandidate(true, memoryKey, content,
                    normalizeImportance(parseString(slice, IMPORTANCE_PATTERN))));
        }
        List<LongTermMemoryCandidate> candidates = dedupeCandidates(parsed);
        if (candidates.isEmpty()) {
            log.warn("长期记忆输出不完整：shouldRemember=true 但没有任何可用的 memoryKey/content，按失败处理等下轮重抽");
            return LongTermMemoryExtractionResult.failed();
        }
        return LongTermMemoryExtractionResult.remembered(candidates);
    }

    /** 取候选所在对象的文本片段：往前一个 '{'，往后一个 '}'。 */
    private String candidateSlice(String response, int keyIndex) {
        int start = response.lastIndexOf('{', keyIndex);
        int end = response.indexOf('}', keyIndex);
        if (start < 0 || end <= start) {
            return response.substring(keyIndex);
        }
        return response.substring(start, end + 1);
    }

    /**
     * 画像类同一分类键只保留最高分；事件按内容去重，避免同类的不同经历被覆盖。
     * <p>
     * 这里用「小写去空格后的原始键」去重，不把 LongTermMemory 的完整归一化映射外提：
     * 提示词已经把 key 限定在 7 个固定取值，重复出现的通常就是同一个字符串。
     */
    private List<LongTermMemoryCandidate> dedupeCandidates(List<LongTermMemoryCandidate> candidates) {
        Map<String, LongTermMemoryCandidate> byKey = new LinkedHashMap<>();
        for (LongTermMemoryCandidate candidate : candidates) {
            String key = candidate.memoryKey().trim().toLowerCase();
            String identity = isEventKey(key) ? key + ":" + candidate.content().trim() : key;
            LongTermMemoryCandidate existing = byKey.get(identity);
            if (existing == null || importanceOf(candidate) > importanceOf(existing)) {
                byKey.put(identity, candidate);
            }
        }
        return byKey.values().stream().limit(maxCandidates).toList();
    }

    static boolean isEventKey(String key) {
        return "user.event".equals(key) || "project.event".equals(key);
    }

    private int importanceOf(LongTermMemoryCandidate candidate) {
        return candidate.importance() == null ? 0 : candidate.importance();
    }

    /**
     * 分数归一化：解析不到、不是数字或小于等于 0 时用兜底 40，超出 1-100 按边界截断。
     *
     * @param raw 模型返回的原始分数文本，可能为空串
     */
    int normalizeImportance(String raw) {
        if (!TextUtil.hasText(raw)) {
            return DEFAULT_IMPORTANCE;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            return DEFAULT_IMPORTANCE;
        }
        if (parsed <= 0) {
            return DEFAULT_IMPORTANCE;
        }
        return Math.min(MAX_IMPORTANCE, Math.max(MIN_IMPORTANCE, parsed));
    }

    /**
     * 把一批消息拼成模型输入。
     *
     * <p>角色标签统一成中文：短期记忆里是 Human/AI，消息表里是 user/assistant，
     * 中文标签让提示词与模型输入保持同一语言，避免模型把 user 当成用户名之类的误读。
     */
    private String formatMessages(List<MemoryMessage> messages) {
        return messages.stream()
                .map(message -> MemoryObservationContext.prefix(message) + roleLabel(message.role()) + ": " + message.content())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String roleLabel(String role) {
        String normalized = TextUtil.trimToDefault(role, "").toLowerCase();
        return switch (normalized) {
            case "human", "user" -> "用户";
            case "ai", "assistant" -> "助手";
            default -> normalized;
        };
    }

    /**
     * 从 LLM 响应片段中提取指定 JSON 字段的字符串值。
     *
     * 正则未匹配到时返回空串（非 null），避免下游拼接时出现 "null" 字符串。
     * 需要通过 {@code trimToDefault} 处理是因为 LLM 输出中的 JSON 字符串值可能含首尾空白。
     */
    private String parseString(String response, Pattern pattern) {
        Matcher matcher = pattern.matcher(response);
        if (!matcher.find()) {
            return "";
        }
        return TextUtil.trimToDefault(matcher.group(1), "");
    }
}
