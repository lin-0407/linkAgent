package com.link.linkagent.memory;

import com.link.linkagent.memory.model.ConversationMessageRecord;
import com.link.linkagent.llm.ChatRequestEnvelope;
import com.link.linkagent.llm.DeepSeekThinkingOptionsFactory;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.LlmCallResult;
import com.link.linkagent.llm.TokenCounter;
import com.link.linkagent.llm.usage.LlmApiUsageService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.llm.usage.LlmUsageFields;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.util.TextUtil;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 摘要记忆 —— 把长对话压缩为摘要，解决上下文窗口有限导致的早期信息丢失。
 *
 * <h3>在记忆架构中的位置</h3>
 * 记忆拼接顺序为「摘要 → 短期记忆 → 长期记忆 → 用户输入」。
 * 摘要位于最前面，是早期对话的压缩版；长期记忆每轮按当前问题检索、内容会变，所以放在最后，
 * 让前面「摘要 + 短期记忆」成为跨轮稳定的前缀（DeepSeek 前缀缓存才能命中，见下）。
 *
 * <h3>核心设计（2026-09 记忆重构）</h3>
 * <ul>
 *   <li><b>按 token 触发</b>：触发条件不再是消息条数，而是「本次请求要发出去的上下文 token 数」
 *       超过 {@code trigger-token-threshold}（默认 256k）。判断发生在组装上下文时，
 *       见 {@code AgentExecutor#buildConversationContext}（设计文档 A2 / A3）。</li>
 *   <li><b>累积式摘要</b>：压缩时必须把已有摘要一起喂给模型并要求合并，否则第二次压缩会丢掉
 *       第一次压缩留下的信息——压缩后原文不再保留，摘要就是那批消息的唯一载体（设计文档 A5 / D4）。</li>
 *   <li><b>先复核再裁剪</b>：摘要必须通过复核（B4）调用方才会裁剪短期记忆；
 *       生成或复核失败时宁可保留原文继续超阈值，也不能出现「原文已删、摘要没有」的空档。</li>
 *   <li><b>前缀复用（2026-10，DSH 式）</b>：有上一轮真实请求前缀时，摘要生成不再自己拼一套
 *       「系统提示词 + 消息」的独立 prompt，而是重放真实请求的前缀、末尾追加压缩指令，
 *       让 25 万 token 的大输入命中 DeepSeek 前缀缓存；复核再把复核要求追加在生成调用的末尾，
 *       复用生成刚写进缓存的那一份 prompt。没有可复用前缀（评测首次压缩）时退回冷启动路径。</li>
 *   <li><b>持久化（B5）</b>：摘要以 {@code role='summary'} 的检查点消息写进会话历史，
 *       重启后仍能读到；内存 {@code ConcurrentHashMap} 只在查库失败时兜底。</li>
 *   <li><b>调用超时</b>：摘要生成本身也走 {@link MemoryCallTimeout} 的 5 分钟兜底，
 *       provider 卡住时不会让请求线程无限等待。</li>
 * </ul>
 */
@Component
public class SummaryMemory {

    private static final Logger log = LoggerFactory.getLogger(SummaryMemory.class);

    /**
     * 摘要记忆配置：开关、触发 token 阈值、压缩后保留消息条数、事实点高分线。
     */
    private final SummaryMemoryProperties properties;

    /**
     * 专门用于生成摘要的 ChatModel 实例。
     * 与主 Agent 使用的 LLM 实例分离，允许为摘要任务配置一个轻量/便宜的模型，降低压缩成本。
     */
    private final ChatModel memorySummaryModel;

    private final DeepSeekThinkingOptionsFactory thinkingOptionsFactory;

    private final PromptService promptService;

    /**
     * 调用流水服务：最小构造路径的摘要生成不走 LLMService，没有它这次调用就不会进 llm_api_call_log，
     * 缓存命中率也就无从统计（生产路径统一走 LLMService 重放，用量由那边记录）。
     */
    private final LlmApiUsageService llmApiUsageService;

    /**
     * 真实请求信封重放入口：生产路径的摘要生成/复核都通过它发请求。
     * <p>
     * 为什么不再直接调 memorySummaryModel：摘要要命中真实请求的前缀缓存，必须复用上一轮请求的
     * system/tools/thinking 形状；这些形状只有发起真实请求的 LLMService 知道。为 null 时（最小构造/单测）
     * 退回直连 memorySummaryModel 的冷启动路径。
     */
    private final LLMService llmService;

    /**
     * 本地 token 计量器：校验摘要正文体积。
     * <p>
     * 只靠供应商 finish_reason 不够：模型可以在上限内写出一份 6 万 token 的“摘要”，
     * 照样不是压缩。这里用官方词表在本地量一遍，超过 {@code max-summary-tokens} 直接判不合格，
     * 不落库、不裁剪。为 null 时（最小构造/单测）跳过体积校验。
     */
    private final TokenCounter tokenCounter;

    /**
     * 运行期设置服务，支持不重启服务即可开关摘要功能。
     * 为 null 时（单测构造器场景）回退到 {@code properties} 的静态配置值。
     */
    private final RuntimeSettingService runtimeSettingService;

    /**
     * 会话消息表：摘要检查点（role=summary）落在这里，重启后仍能读到。
     * 单测构造器不注入时为 null，此时退化为纯内存摘要。
     */
    private final ConversationSessionMapper conversationSessionMapper;

    /**
     * 摘要复核器（设计文档 B4）。
     * 单测构造器不注入时为 null，此时跳过复核——复核依赖模型调用，最小构造路径不引入这个依赖。
     */
    private final SummaryReviewer summaryReviewer;

    /** 摘要检查点在会话消息表里的角色值，与 mapper 的查询条件保持一致。 */
    public static final String SUMMARY_MESSAGE_ROLE = "summary";

    /**
     * 会话级摘要缓存，按 sessionId 隔离。
     * 同一 session 的多次请求可能并发到达，ConcurrentHashMap 保证线程安全。
     * 接了数据库时它只是查询失败时的兜底，不再是唯一真相。
     */
    private final Map<String, String> sessionSummaries = new ConcurrentHashMap<>();

    /**
     * 生产环境构造器（由 Spring 自动注入）。
     * RuntimeSettingService 非空时，摘要开关可以从运行期配置中心动态调整。
     */
    @Autowired
    public SummaryMemory(SummaryMemoryProperties properties,
                         ChatModel memorySummaryModel,
                         PromptService promptService,
                         SummaryReviewer summaryReviewer,
                         RuntimeSettingService runtimeSettingService,
                         ConversationSessionMapper conversationSessionMapper,
                         DeepSeekThinkingOptionsFactory thinkingOptionsFactory,
                         LlmApiUsageService llmApiUsageService,
                         LLMService llmService,
                         TokenCounter tokenCounter) {
        this.properties = properties;
        this.memorySummaryModel = memorySummaryModel;
        this.promptService = promptService;
        this.summaryReviewer = summaryReviewer;
        this.runtimeSettingService = runtimeSettingService;
        this.conversationSessionMapper = conversationSessionMapper;
        this.thinkingOptionsFactory = thinkingOptionsFactory;
        this.llmApiUsageService = llmApiUsageService;
        this.llmService = llmService;
        this.tokenCounter = tokenCounter;
    }

    /** 九参重载：不注入 token 计数器的测试/工具构造，摘要体积校验跳过。 */
    public SummaryMemory(SummaryMemoryProperties properties, ChatModel memorySummaryModel, PromptService promptService,
                         SummaryReviewer summaryReviewer, RuntimeSettingService runtimeSettingService,
                         ConversationSessionMapper conversationSessionMapper,
                         DeepSeekThinkingOptionsFactory thinkingOptionsFactory,
                         LlmApiUsageService llmApiUsageService, LLMService llmService) {
        this(properties, memorySummaryModel, promptService, summaryReviewer, runtimeSettingService,
                conversationSessionMapper, thinkingOptionsFactory, llmApiUsageService, llmService, null);
    }

    /** 现有单测手动构造时不依赖思考配置与用量服务，生产由上面的构造器显式注入。 */
    public SummaryMemory(SummaryMemoryProperties properties, ChatModel memorySummaryModel, PromptService promptService,
                         SummaryReviewer summaryReviewer, RuntimeSettingService runtimeSettingService,
                         ConversationSessionMapper conversationSessionMapper) {
        this(properties, memorySummaryModel, promptService, summaryReviewer, runtimeSettingService,
                conversationSessionMapper, null, null, null, null);
    }

    public SummaryMemory(SummaryMemoryProperties properties, ChatModel memorySummaryModel, PromptService promptService,
                         SummaryReviewer summaryReviewer, RuntimeSettingService runtimeSettingService,
                         ConversationSessionMapper conversationSessionMapper,
                         DeepSeekThinkingOptionsFactory thinkingOptionsFactory) {
        this(properties, memorySummaryModel, promptService, summaryReviewer, runtimeSettingService,
                conversationSessionMapper, thinkingOptionsFactory, null, null, null);
    }

    /**
     * 测试/最小化构造器，不注入 RuntimeSettingService。
     * 此时回退到 {@code properties.enabled()} 的静态配置值，避免单元测试必须感知运行期设置模块。
     */
    public SummaryMemory(SummaryMemoryProperties properties, ChatModel memorySummaryModel, PromptService promptService) {
        this.properties = properties;
        this.memorySummaryModel = memorySummaryModel;
        this.thinkingOptionsFactory = null;
        this.promptService = promptService;
        // 单测构造器不接复核器与数据库：摘要在内存里生效，复核与持久化路径由生产构造器覆盖。
        this.summaryReviewer = null;
        this.runtimeSettingService = null;
        this.conversationSessionMapper = null;
        this.llmApiUsageService = null;
        this.llmService = null;
        this.tokenCounter = null;
    }

    /**
     * 获取指定会话的当前摘要文本。
     *
     * @param sessionId 会话标识
     * @return 摘要文本；摘要功能关闭或尚无摘要时返回空串（非 null，避免拼接时出现 "null" 字符串）
     */
    public String getSummary(String sessionId) {
        if (!isSummaryMemoryEnabled()) {
            return "";
        }
        if (conversationSessionMapper == null) {
            return sessionSummaries.getOrDefault(sessionId, "");
        }
        // 摘要检查点是会话历史的一部分，重启后仍然存在；内存 Map 只作为查库失败时的兜底，
        // 避免一次数据库抖动就让模型彻底丢掉历史。
        try {
            return conversationSessionMapper.findLatestSummary(sessionId)
                    .map(ConversationMessageRecord::getContent)
                    .map(content -> TextUtil.trimToDefault(content, ""))
                    .orElse("");
        } catch (Exception exception) {
            log.warn("读取摘要检查点失败，回退内存缓存，sessionId={}, error={}", sessionId, exception.getMessage());
            return sessionSummaries.getOrDefault(sessionId, "");
        }
    }

    /**
     * 对给定消息生成摘要、复核并保存，返回最终摘要文本（冷启动路径，没有可复用的真实请求前缀）。
     *
     * @param sessionId 会话标识
     * @param messages  本次要压缩掉的消息（按时间顺序）
     * @return 通过复核的新摘要文本；功能关闭、消息为空、生成或复核失败时返回 null（调用方据此决定不裁剪）
     */
    public String summarize(String sessionId, List<MemoryMessage> messages) {
        return summarize(sessionId, messages, null);
    }

    /**
     * 对给定消息生成摘要、复核并保存，返回最终摘要文本。
     *
     * <h3>前缀复用（2026-10）</h3>
     * {@code replay} 非空时，生成调用 = 上一轮真实请求的 system + 「摘要 + 被压缩消息原文」前缀，
     * 末尾追加压缩指令。这样整段前缀与上一轮真实请求逐字节一致，DeepSeek 前缀缓存直接命中；
     * 复核随后在同一消息列表后面追加复核要求，复用生成调用刚建立的缓存。
     * {@code replay} 为空时退回原来的独立 prompt（评测首次压缩没有上一轮真实请求可用）。
     *
     * @param sessionId 会话标识
     * @param messages  本次要压缩掉的消息（按时间顺序）
     * @param replay    可复用的上一轮真实请求前缀；null 表示冷启动
     * @return 通过复核的新摘要文本；功能关闭、消息为空、生成或复核失败时返回 null（调用方据此决定不裁剪）
     */
    public String summarize(String sessionId, List<MemoryMessage> messages, SummaryReplayPrefix replay) {
        if (!isSummaryMemoryEnabled() || messages == null || messages.isEmpty()) {
            return null;
        }
        // 上一版摘要只在这里读一次：摘要生成和复核必须用同一份基准，否则核对的是两个不同的事实集合。
        String existingSummary = getSummary(sessionId);
        SummaryGeneration generation = generate(sessionId, existingSummary, messages, List.of(), replay);
        if (generation == null) {
            return null;
        }
        SummaryReviewResult review = reviewSummary(generation, existingSummary, messages);
        if (review.passed()) {
            saveSummary(sessionId, generation.summary());
            return generation.summary();
        }
        log.warn("摘要复核未通过，带问题重新生成一次，sessionId={}, 问题={}", sessionId, review.issues());
        SummaryGeneration regenerated = generate(sessionId, existingSummary, messages, review.issues(), replay);
        if (regenerated == null) {
            return null;
        }
        SummaryReviewResult secondReview = reviewSummary(regenerated, existingSummary, messages);
        if (!secondReview.passed()) {
            log.warn("摘要复核第二次仍未通过，本轮不裁剪原文，sessionId={}, 问题={}", sessionId, secondReview.issues());
            return null;
        }
        saveSummary(sessionId, regenerated.summary());
        return regenerated.summary();
    }

    /**
     * 单次摘要生成（不落库、不覆盖已有摘要）。
     * 复核不通过时这个方法会被调用第二次，因此必须把「生成」与「保存」分开——
     * 先把被否掉的版本写进缓存或检查点，会让下一轮上下文带上一份没通过的摘要。
     *
     * @param existingSummary 上一版已通过复核的摘要；为空表示首次压缩
     * @param reviewIssues    上一次复核发现的问题；为空表示首次生成
     * @param replay          可复用的真实请求前缀；null 表示冷启动
     */
    private SummaryGeneration generate(String sessionId, String existingSummary, List<MemoryMessage> messages,
                                       List<String> reviewIssues, SummaryReplayPrefix replay) {
        try {
            return MemoryCallTimeout.call("摘要生成", () -> {
                try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("摘要生成")) {
                    // 最小构造/单测路径没有 LLMService：直连摘要模型，保持改造前行为。
                    if (llmService == null) {
                        return generateDirect(sessionId, existingSummary, messages, reviewIssues);
                    }
                    // 优先用上一轮真实请求的信封重放：system/tools/thinking 与真实请求一致，
                    // 25 万 token 的前缀才能命中供应商缓存；压缩指令作为追加消息放在末尾。
                    if (replay != null) {
                        SummaryGeneration replayed = generateByReplay(sessionId, replay, reviewIssues);
                        if (replayed != null) {
                            return replayed;
                        }
                        log.warn("摘要重放没有返回正文（模型可能选择了工具调用），回退冷启动，sessionId={}", sessionId);
                    }
                    // 冷启动：评测首次压缩、进程刚重启，或重放失败。
                    return generateCold(sessionId, existingSummary, messages, reviewIssues);
                }
            });
        } catch (Exception exception) {
            // 摘要失败不向上抛异常——Agent 主流程不应因摘要失败而中断对话。
            // 最坏情况：上下文偏大，但仍在上限保护范围内。
            log.error("摘要记忆压缩失败，sessionId={}, error={}", sessionId, exception.getMessage());
            return null;
        }
    }

    /**
     * 用真实请求信封重放生成：消息 = [上一轮真实上下文前缀, 压缩指令]。
     * <p>
     * 前缀消息必须与真实请求逐字节一致，否则供应商端前缀从工具/思考声明处就分叉，缓存一次都命中不了。
     */
    private SummaryGeneration generateByReplay(String sessionId, SummaryReplayPrefix replay,
                                               List<String> reviewIssues) {
        // 真实请求的完整消息原样保留，压缩指令作为一条新追加的用户消息放在最后：
        // 既保证供应商端前缀逐字节一致，也让模型在读完原文后看到明确的输出要求。
        List<Message> callMessages = new ArrayList<>(replay.envelope().messages());
        callMessages.add(new UserMessage(buildReplayInstruction(reviewIssues)));
        LlmCallResult result = llmService.replayChat(replay.envelope(), List.copyOf(callMessages),
                outputTokenLimit());
        return toGeneration(sessionId, result, replay.envelope().systemPrompt(), List.copyOf(callMessages),
                replay.envelope());
    }

    /** 冷启动生成：没有上一轮真实请求可复用，用独立的「规则 + 消息」prompt。 */
    private SummaryGeneration generateCold(String sessionId, String existingSummary, List<MemoryMessage> messages,
                                           List<String> reviewIssues) {
        String systemPrompt = promptService.get("summary_memory.system");
        List<Message> callMessages = List.of(new UserMessage(
                buildColdUserPrompt(existingSummary, messages, reviewIssues)));
        ChatRequestEnvelope envelope = coldEnvelope(systemPrompt, callMessages);
        LlmCallResult result = llmService.replayChat(envelope, callMessages, outputTokenLimit());
        return toGeneration(sessionId, result, systemPrompt, callMessages, envelope);
    }

    /** 最小构造/单测路径：直接调注入的摘要 ChatModel，不经过 LLMService 的信封重放。 */
    private SummaryGeneration generateDirect(String sessionId, String existingSummary, List<MemoryMessage> messages,
                                             List<String> reviewIssues) {
        String systemPrompt = promptService.get("summary_memory.system");
        List<Message> callMessages = new ArrayList<>();
        // 冷启动：没有上一轮真实请求可复用，保持原来的「规则 + 消息」集中在一段提示词里。
        callMessages.add(new SystemMessage(systemPrompt));
        callMessages.add(new UserMessage(buildColdUserPrompt(existingSummary, messages, reviewIssues)));
        long startNanos = System.nanoTime();
        ChatResponse response = memorySummaryModel.call(buildPrompt(callMessages));
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        recordUsage(response, elapsedMs);
        String generated = response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                ? null : response.getResult().getOutput().getText();
        if (TextUtil.isBlank(generated)) {
            log.warn("摘要模型返回空内容，本轮不压缩，sessionId={}", sessionId);
            return null;
        }
        String finishReason = response.getResult().getMetadata() == null ? null
                : response.getResult().getMetadata().getFinishReason();
        if ("length".equalsIgnoreCase(finishReason) || "max_tokens".equalsIgnoreCase(finishReason)) {
            log.warn("摘要输出被模型输出上限截断（finishReason={}），本轮不压缩、不落库，sessionId={}",
                    finishReason, sessionId);
            return null;
        }
        if (summaryExceedsTokenBudget(sessionId, generated)) {
            return null;
        }
        return new SummaryGeneration(generated.trim(), systemPrompt, List.copyOf(callMessages));
    }

    /** 把一次重放结果转成摘要；空内容/被截断/超出摘要预算都返回 null（调用方据此不裁剪）。 */
    private SummaryGeneration toGeneration(String sessionId, LlmCallResult result, String systemPrompt,
                                           List<Message> callMessages, ChatRequestEnvelope envelope) {
        if (result == null || TextUtil.isBlank(result.content())) {
            // 模型返回空摘要时不能覆盖已有摘要，也不能让调用方去裁剪原文。
            log.warn("摘要模型返回空内容，本轮不压缩，sessionId={}", sessionId);
            return null;
        }
        if (result.truncatedByOutputLimit()) {
            // 被输出上限截断的摘要必然不完整，再送复核只会得到「漏抽」然后触发更贵的重生成。
            log.warn("摘要输出被模型输出上限截断（finishReason={}），本轮不压缩、不落库，sessionId={}",
                    result.finishReason(), sessionId);
            return null;
        }
        if (summaryExceedsTokenBudget(sessionId, result.content())) {
            return null;
        }
        return new SummaryGeneration(result.content().trim(), systemPrompt, callMessages, envelope);
    }

    /** 摘要生成请求的输出上限；0 表示不显式设置（交给供应商默认值）。 */
    private Integer outputTokenLimit() {
        int limit = properties.maxOutputTokens();
        return limit > 0 ? limit : null;
    }

    /** 摘要正文体积校验：超预算就判不合格，避免把「6 万 token 的摘要」当成压缩结果落库。 */
    private boolean summaryExceedsTokenBudget(String sessionId, String summary) {
        int budget = properties.maxSummaryTokens();
        if (budget <= 0 || tokenCounter == null) {
            return false;
        }
        int tokens = tokenCounter.count(summary);
        if (tokens <= budget) {
            return false;
        }
        log.warn("摘要正文超过预算 {} token（实际 {}），本轮不压缩、不落库，sessionId={}",
                budget, tokens, sessionId);
        return true;
    }

    /** 冷启动信封：普通 ChatClient 出口，思考等级沿用摘要模型的默认设置。 */
    private ChatRequestEnvelope coldEnvelope(String systemPrompt, List<Message> messages) {
        OpenAiChatOptions options = thinkingOptionsFactory == null
                ? null
                : thinkingOptionsFactory.optionsForDefaultModel();
        return ChatRequestEnvelope.main(systemPrompt, messages, options);
    }

    /** 组装 ChatModel 请求：思考等级沿用原来的逐次注入，并带上输出上限防止跑飞。 */
    private Prompt buildPrompt(List<Message> messages) {
        if (thinkingOptionsFactory == null) {
            return new Prompt(messages);
        }
        // 摘要直接使用 ChatModel，必须逐次传入 low，不能遗漏这条绕过 LLMService 的调用。
        org.springframework.ai.openai.OpenAiChatOptions options = thinkingOptionsFactory.optionsForDefaultModel();
        int limit = properties.maxOutputTokens();
        if (limit > 0) {
            options.setMaxTokens(limit);
        }
        return new Prompt(messages, options);
    }

    /** 把本次摘要生成的 usage 记进调用流水，重点记 cachedPromptTokens 才能算命中率。 */
    private void recordUsage(ChatResponse response, long elapsedMs) {
        if (response == null) {
            return;
        }
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        Integer promptTokens = LlmUsageFields.promptTokens(usage);
        Integer completionTokens = LlmUsageFields.completionTokens(usage);
        Integer totalTokens = LlmUsageFields.totalTokens(usage);
        Integer cachedPromptTokens = LlmUsageFields.cachedPromptTokens(usage);
        String modelName = metadata == null ? null : metadata.getModel();
        // 摘要生成不走 LLMService，没有这行日志就看不到它的缓存命中；生产排查前缀复用是否生效全靠它。
        log.info("摘要生成调用用量：model={}, promptTokens={}, completionTokens={}, cachedPromptTokens={}, cacheHitRate={}, elapsedMs={}",
                modelName, promptTokens, completionTokens, cachedPromptTokens,
                cacheHitRate(cachedPromptTokens, promptTokens), elapsedMs);
        if (llmApiUsageService == null) {
            return;
        }
        llmApiUsageService.recordTextSuccess(
                modelName,
                promptTokens,
                completionTokens,
                totalTokens,
                cachedPromptTokens,
                null,
                elapsedMs);
    }

    /** 缓存命中率：命中 token / 输入 token；供应商没返回缓存字段时显示 unknown，而不是误报 0%。 */
    private String cacheHitRate(Integer cachedPromptTokens, Integer promptTokens) {
        if (cachedPromptTokens == null || promptTokens == null || promptTokens <= 0) {
            return "unknown";
        }
        return String.format(java.util.Locale.ROOT, "%.1f%%", cachedPromptTokens * 100.0 / promptTokens);
    }

    /** 复核摘要；最小构造路径没有复核器时按通过处理，保持单测路径与改造前一致。 */
    private SummaryReviewResult reviewSummary(SummaryGeneration generation, String existingSummary,
                                              List<MemoryMessage> messages) {
        if (summaryReviewer == null) {
            return SummaryReviewResult.pass();
        }
        return summaryReviewer.review(generation, existingSummary, messages);
    }

    /**
     * 冷启动提示词：已有摘要 + 本次消息 + 返工要求，集中放在一条用户消息里。
     *
     * <pre>
     * 已有摘要（必须合并进新摘要，不能丢弃其中的信息）：
     * ...
     *
     * 当前对话消息如下：
     * Human: ...
     * AI: ...
     * </pre>
     */
    private String buildColdUserPrompt(String existingSummary, List<MemoryMessage> messages, List<String> reviewIssues) {
        StringBuilder prompt = new StringBuilder();
        if (TextUtil.hasText(existingSummary)) {
            prompt.append("已有摘要（必须合并进新摘要，不能丢弃其中的信息）：\n")
                    .append(existingSummary)
                    .append("\n\n");
        }
        prompt.append("当前对话消息如下：\n").append(renderMessages(messages));
        appendReviewIssues(prompt, reviewIssues);
        return prompt.toString();
    }

    /**
     * 前缀复用提示词：前面是真实对话前缀，这里只补压缩指令。
     * 指令内容复用 summary_memory.system 的规则文本（它本来就是「怎么压缩」的说明），
     * 放到末尾而不是 system，是因为 system 必须与上一轮真实请求保持一致才能命中缓存。
     */
    private String buildReplayInstruction(List<String> reviewIssues) {
        StringBuilder instruction = new StringBuilder("\n\n");
        instruction.append(promptService.get("summary_memory.system"));
        instruction.append("\n\n上面这段内容就是需要压缩的对话原文（开头如果有 Conversation summary 块，那是上一版摘要，"
                + "必须合并进新摘要）。现在只输出摘要正文本身，不要解释生成过程，也不要调用任何工具。");
        appendReviewIssues(instruction, reviewIssues);
        return instruction.toString();
    }

    private void appendReviewIssues(StringBuilder prompt, List<String> reviewIssues) {
        if (reviewIssues == null || reviewIssues.isEmpty()) {
            return;
        }
        // 复核问题放在消息之后：模型先看到待压缩原文，再看返工要求，比反过来更不容易漏改。
        prompt.append("\n\n上一次生成的摘要存在以下问题，请重新生成并修正：\n");
        for (String issue : reviewIssues) {
            prompt.append("- ").append(issue).append("\n");
        }
        // 2026-09-19 联调实测（作者日志）：只说「按问题修正」时，模型会把「本次已修正 XX 问题」这类
        // 说明写进摘要正文，复核下一轮把它判成编造（原文里没有这段话），于是压缩永远过不了。
        // 返工要求必须同时限定输出内容：只要摘要正文本身。
        prompt.append("\n只输出修正后的摘要正文本身：不要解释你改了什么，"
                + "不要写「修正说明」「已按某问题调整」这类关于生成过程的句子。\n");
    }

    /** 消息渲染与短期记忆进上下文时保持同一格式，工具调用渲染成 ToolCall: 工具(参数)。 */
    private String renderMessages(List<MemoryMessage> messages) {
        return messages.stream()
                .map(message -> MemoryObservationContext.prefix(message) + message.role() + ": " + message.promptText())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    /**
     * 保存摘要到会话级缓存。
     * 摘要功能关闭或文本为空时跳过，避免在禁用状态下被外部写入脏数据。
     */
    public void saveSummary(String sessionId, String summary) {
        if (!isSummaryMemoryEnabled() || TextUtil.isBlank(summary)) {
            return;
        }
        sessionSummaries.put(sessionId, summary.trim());
    }

    /**
     * 把一次压缩记成会话历史里的摘要检查点。
     * <p>
     * DSH 式语义：摘要是会话里的一条消息，不是后端内存里的黑盒；被压缩的原文仍完整保留在消息表中，
     * 只是不再进入模型上下文。{@code token_count} 记录本次压缩释放的 token 数，供前端说明「释放了多少」。
     *
     * @param sessionId              会话标识
     * @param userId                 用户标识，落库到消息表的 user_id（设计文档 C5）
     * @param summary                摘要正文
     * @param compressedMessageCount 本次被压缩掉的消息条数
     * @param tokensBefore           压缩前的上下文 token 数
     * @param tokensAfter            压缩后的上下文 token 数
     */
    public void recordCompactionCheckpoint(String sessionId, String userId, String summary, int compressedMessageCount,
                                           int tokensBefore, int tokensAfter) {
        if (TextUtil.isBlank(summary)) {
            return;
        }
        String normalized = summary.trim();
        sessionSummaries.put(sessionId, normalized);
        if (conversationSessionMapper == null) {
            return;
        }
        try {
            ConversationMessageRecord record = new ConversationMessageRecord();
            record.setSessionId(sessionId);
            record.setUserId(userId);
            record.setRole(SUMMARY_MESSAGE_ROLE);
            record.setContent(normalized);
            record.setTokenCount(Math.max(0, tokensBefore - tokensAfter));
            record.setCreateTime(LocalDateTime.now());
            conversationSessionMapper.insertMessage(record);
            log.info("摘要检查点已写入会话历史，sessionId={}, 压缩 {} 条消息, 释放 {} token",
                    sessionId, compressedMessageCount, record.getTokenCount());
        } catch (Exception exception) {
            // 写库失败不影响本轮对话：摘要已经在内存里可用，下一轮仍能带上上下文。
            log.warn("摘要检查点写入失败，本次仅在内存生效，sessionId={}, error={}", sessionId, exception.getMessage());
        }
    }

    /**
     * 获取压缩后短期记忆应保留的消息条数。
     * Math.max(0, n) 防止配置误配负数导致裁剪时算出负索引。
     * <p>
     * 生产路径由 {@link #getRetainedTokenBudget()} 决定实际保留多少条，这个值只在拿不到 token 计量器时兜底。
     */
    public int getRetainedMessageCount() {
        return Math.max(0, properties.retainedMessageCount());
    }

    /**
     * 获取压缩后保留尾部的 token 预算（默认 16k，可配置）。
     * 返回 0 表示退回按条数保留（单测/最小构造或显式关闭 token 预算）。
     */
    public int getRetainedTokenBudget() {
        return Math.max(0, properties.retainedTokenBudget());
    }

    /** 评测会话清理时同步释放内存摘要，避免只删消息表后仍能读到旧摘要。 */
    public void forgetSession(String sessionId) {
        sessionSummaries.remove(sessionId);
    }

    /**
     * 获取压缩失败后的重试冷却增量（token）。
     * 返回 0 表示不冷却、每轮都可能重试。
     */
    public int getRetryTokenDelta() {
        return Math.max(0, properties.retryTokenDelta());
    }

    /**
     * 获取触发压缩的 token 阈值。Math.max(1, n) 防止配置误配 0 时变成「每轮都压缩」。
     */
    public int getTokenThreshold() {
        return Math.max(1, properties.triggerTokenThreshold());
    }

    /**
     * 获取事实点重要性高分线（1-100，设计文档 B3）。
     * 由 {@code AgentExecutor} 在压缩完成后用它决定哪些事实点写入长期记忆。
     */
    public int getFactImportanceThreshold() {
        return properties.factImportanceThreshold();
    }

    /**
     * 判断摘要记忆功能是否启用。
     * 生产环境走 RuntimeSettingService（可热更），单测未注入时回退到 properties 的静态配置值。
     */
    private boolean isSummaryMemoryEnabled() {
        return runtimeSettingService == null
                ? properties.enabled()
                : runtimeSettingService.isSummaryMemoryEnabled();
    }
}
