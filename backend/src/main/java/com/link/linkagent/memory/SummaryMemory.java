package com.link.linkagent.memory;

import com.link.linkagent.memory.model.ConversationMessageRecord;
import com.link.linkagent.llm.DeepSeekThinkingOptionsFactory;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.util.TextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 摘要记忆 —— 把长对话压缩为摘要，解决上下文窗口有限导致的早期信息丢失。
 *
 * <h3>在记忆架构中的位置</h3>
 * 记忆拼接顺序为「长期记忆 → 摘要 → 短期记忆 → 用户输入」。
 * 摘要位于长期记忆之后、短期记忆之前，是早期对话的压缩版。
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
                         DeepSeekThinkingOptionsFactory thinkingOptionsFactory) {
        this.properties = properties;
        this.memorySummaryModel = memorySummaryModel;
        this.promptService = promptService;
        this.summaryReviewer = summaryReviewer;
        this.runtimeSettingService = runtimeSettingService;
        this.conversationSessionMapper = conversationSessionMapper;
        this.thinkingOptionsFactory = thinkingOptionsFactory;
    }

    /** 现有单测手动构造时不依赖思考配置，生产由上面的构造器显式注入。 */
    public SummaryMemory(SummaryMemoryProperties properties, ChatModel memorySummaryModel, PromptService promptService,
                         SummaryReviewer summaryReviewer, RuntimeSettingService runtimeSettingService,
                         ConversationSessionMapper conversationSessionMapper) {
        this(properties, memorySummaryModel, promptService, summaryReviewer, runtimeSettingService,
                conversationSessionMapper, null);
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
     * 对给定消息生成摘要、复核并保存，返回最终摘要文本。
     *
     * <h3>为什么方法名不体现「是否触发」</h3>
     * 触发判断已经上移到 {@code AgentExecutor}（按 token 计量）：本方法只要被调用就一定会发起模型调用，
     * 不再承担「判断是否触发」的职责。
     *
     * <h3>累积合并</h3>
     * 提示词里会带上已有摘要并要求一并合并进新摘要。压缩后原文不再保留，
     * 不合并就会让更早的历史在第二次压缩时彻底消失。
     *
     * <h3>复核与重生成（设计文档 B4）</h3>
     * 生成后先复核：不通过就带着复核给出的问题重新生成一次，仍不通过返回 null，
     * 由调用方保留原文（宁可不压缩，也不接受一份漏抽或编造的摘要）。
     * 摘要只有通过复核才会写进缓存/检查点，被否掉的版本不会覆盖已有摘要。
     *
     * @param sessionId 会话标识
     * @param messages  本次要压缩掉的消息（按时间顺序）
     * @return 通过复核的新摘要文本；功能关闭、消息为空、生成或复核失败时返回 null（调用方据此决定不裁剪）
     */
    public String summarize(String sessionId, List<MemoryMessage> messages) {
        if (!isSummaryMemoryEnabled() || messages == null || messages.isEmpty()) {
            return null;
        }
        // 上一版摘要只在这里读一次：摘要生成和复核必须用同一份基准，否则核对的是两个不同的事实集合。
        String existingSummary = getSummary(sessionId);
        String summary = generate(sessionId, existingSummary, messages, List.of());
        if (TextUtil.isBlank(summary)) {
            return null;
        }
        SummaryReviewResult review = reviewSummary(summary, existingSummary, messages);
        if (review.passed()) {
            saveSummary(sessionId, summary);
            return summary;
        }
        log.warn("摘要复核未通过，带问题重新生成一次，sessionId={}, 问题={}", sessionId, review.issues());
        String regenerated = generate(sessionId, existingSummary, messages, review.issues());
        if (TextUtil.isBlank(regenerated)) {
            return null;
        }
        SummaryReviewResult secondReview = reviewSummary(regenerated, existingSummary, messages);
        if (!secondReview.passed()) {
            log.warn("摘要复核第二次仍未通过，本轮不裁剪原文，sessionId={}, 问题={}", sessionId, secondReview.issues());
            return null;
        }
        saveSummary(sessionId, regenerated);
        return regenerated;
    }

    /**
     * 单次摘要生成（不落库、不覆盖已有摘要）。
     * 复核不通过时这个方法会被调用第二次，因此必须把「生成」与「保存」分开——
     * 先把被否掉的版本写进缓存或检查点，会让下一轮上下文带上一份没通过的摘要。
     *
     * @param existingSummary 上一版已通过复核的摘要；为空表示首次压缩
     * @param reviewIssues    上一次复核发现的问题；为空表示首次生成
     */
    private String generate(String sessionId, String existingSummary, List<MemoryMessage> messages,
                            List<String> reviewIssues) {
        try {
            String generated = MemoryCallTimeout.call("摘要生成",
                    () -> {
                        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("摘要生成")) {
                            String prompt = buildPrompt(existingSummary, messages, reviewIssues);
                            if (thinkingOptionsFactory == null) {
                                return memorySummaryModel.call(prompt);
                            }
                            // 摘要直接使用 ChatModel，必须逐次传入 low，不能遗漏这条绕过 LLMService 的调用。
                            return memorySummaryModel.call(new Prompt(prompt, thinkingOptionsFactory.optionsForDefaultModel()))
                                    .getResult().getOutput().getText();
                        }
                    });
            if (TextUtil.isBlank(generated)) {
                // 模型返回空摘要时不能覆盖已有摘要，也不能让调用方去裁剪原文。
                log.warn("摘要模型返回空内容，本轮不压缩，sessionId={}", sessionId);
                return null;
            }
            return generated.trim();
        } catch (Exception exception) {
            // 摘要失败不向上抛异常——Agent 主流程不应因摘要失败而中断对话。
            // 最坏情况：上下文偏大，但仍在上限保护范围内。
            log.error("摘要记忆压缩失败，sessionId={}, error={}", sessionId, exception.getMessage());
            return null;
        }
    }

    /** 复核摘要；最小构造路径没有复核器时按通过处理，保持单测路径与改造前一致。 */
    private SummaryReviewResult reviewSummary(String summary, String existingSummary, List<MemoryMessage> messages) {
        if (summaryReviewer == null) {
            return SummaryReviewResult.pass();
        }
        return summaryReviewer.review(summary, existingSummary, messages);
    }

    /**
     * 构建摘要生成的完整提示词。
     *
     * <pre>
     * [summary_memory.system 系统提示词]
     *
     * 已有摘要（必须合并进新摘要，不能丢弃其中的信息）：
     * ...
     *
     * 当前对话消息如下：
     * Human: ...
     * AI: ...
     * </pre>
     */
    private String buildPrompt(String existingSummary, List<MemoryMessage> messages, List<String> reviewIssues) {
        StringBuilder prompt = new StringBuilder(promptService.get("summary_memory.system")).append("\n\n");
        // 已有摘要由调用方一次性读出（可能来自数据库），这里不再重复查询，保证生成与复核用的是同一份基准。
        if (TextUtil.hasText(existingSummary)) {
            prompt.append("已有摘要（必须合并进新摘要，不能丢弃其中的信息）：\n")
                    .append(existingSummary)
                    .append("\n\n");
        }
        prompt.append("当前对话消息如下：\n")
                .append(messages.stream()
                        .map(message -> MemoryObservationContext.prefix(message) + message.role() + ": " + message.content())
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse(""));
        if (reviewIssues != null && !reviewIssues.isEmpty()) {
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
        return prompt.toString();
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
     */
    public int getRetainedMessageCount() {
        return Math.max(0, properties.retainedMessageCount());
    }

    /** 评测会话清理时同步释放内存摘要，避免只删消息表后仍能读到旧摘要。 */
    public void forgetSession(String sessionId) {
        sessionSummaries.remove(sessionId);
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
