package com.link.linkagent.memory;

import com.link.linkagent.prompt.service.PromptService;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.util.TextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
 *   <li><b>先摘要后裁剪</b>：只有摘要成功返回，调用方才会裁剪短期记忆；
 *       摘要失败时宁可保留原文继续超阈值，也不能出现「原文已删、摘要没有」的空档。</li>
 *   <li><b>内存级存储</b>：摘要仍在 {@code ConcurrentHashMap} 中（不落库），服务重启即丢失。
 *       持久化属于设计文档 B5，本切片不处理。</li>
 * </ul>
 */
@Component
public class SummaryMemory {

    private static final Logger log = LoggerFactory.getLogger(SummaryMemory.class);

    /**
     * 摘要记忆配置：开关、触发 token 阈值、压缩后保留消息条数。
     */
    private final SummaryMemoryProperties properties;

    /**
     * 专门用于生成摘要的 ChatModel 实例。
     * 与主 Agent 使用的 LLM 实例分离，允许为摘要任务配置一个轻量/便宜的模型，降低压缩成本。
     */
    private final ChatModel memorySummaryModel;

    private final PromptService promptService;

    /**
     * 运行期设置服务，支持不重启服务即可开关摘要功能。
     * 为 null 时（单测构造器场景）回退到 {@code properties} 的静态配置值。
     */
    private final RuntimeSettingService runtimeSettingService;

    /**
     * 会话级摘要缓存，按 sessionId 隔离。
     * 同一 session 的多次请求可能并发到达，ConcurrentHashMap 保证线程安全。
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
                         RuntimeSettingService runtimeSettingService) {
        this.properties = properties;
        this.memorySummaryModel = memorySummaryModel;
        this.promptService = promptService;
        this.runtimeSettingService = runtimeSettingService;
    }

    /**
     * 测试/最小化构造器，不注入 RuntimeSettingService。
     * 此时回退到 {@code properties.enabled()} 的静态配置值，避免单元测试必须感知运行期设置模块。
     */
    public SummaryMemory(SummaryMemoryProperties properties, ChatModel memorySummaryModel, PromptService promptService) {
        this.properties = properties;
        this.memorySummaryModel = memorySummaryModel;
        this.promptService = promptService;
        this.runtimeSettingService = null;
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
        return sessionSummaries.getOrDefault(sessionId, "");
    }

    /**
     * 对给定消息生成（或刷新）摘要，返回新摘要文本。
     *
     * <h3>为什么方法名不体现「是否触发」</h3>
     * 触发判断已经上移到 {@code AgentExecutor}（按 token 计量）：本方法只要被调用就一定会发起一次
     * 模型调用，不再承担「判断是否触发」的职责。
     *
     * <h3>累积合并</h3>
     * 提示词里会带上已有摘要并要求一并合并进新摘要。压缩后原文不再保留，
     * 不合并就会让更早的历史在第二次压缩时彻底消失。
     *
     * @param sessionId 会话标识
     * @param messages  本次要压缩掉的消息（按时间顺序）
     * @return 新摘要文本；功能关闭、消息为空或模型调用失败时返回 null（调用方据此决定不裁剪）
     */
    public String summarize(String sessionId, List<MemoryMessage> messages) {
        if (!isSummaryMemoryEnabled() || messages == null || messages.isEmpty()) {
            return null;
        }
        try {
            String generated = memorySummaryModel.call(buildPrompt(sessionId, messages));
            if (TextUtil.isBlank(generated)) {
                // 模型返回空摘要时不能覆盖已有摘要，也不能让调用方去裁剪原文。
                log.warn("摘要模型返回空内容，本轮不压缩，sessionId={}", sessionId);
                return null;
            }
            String summary = generated.trim();
            saveSummary(sessionId, summary);
            return summary;
        } catch (Exception exception) {
            // 摘要失败不向上抛异常——Agent 主流程不应因摘要失败而中断对话。
            // 最坏情况：上下文偏大，但仍在上限保护范围内。
            log.error("摘要记忆压缩失败，sessionId={}, error={}", sessionId, exception.getMessage());
            return null;
        }
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
    private String buildPrompt(String sessionId, List<MemoryMessage> messages) {
        StringBuilder prompt = new StringBuilder(promptService.get("summary_memory.system")).append("\n\n");
        String existingSummary = sessionSummaries.getOrDefault(sessionId, "");
        if (TextUtil.hasText(existingSummary)) {
            prompt.append("已有摘要（必须合并进新摘要，不能丢弃其中的信息）：\n")
                    .append(existingSummary)
                    .append("\n\n");
        }
        return prompt.append("当前对话消息如下：\n")
                .append(messages.stream()
                        .map(message -> message.role() + ": " + message.content())
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse(""))
                .toString();
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
     * 获取压缩后短期记忆应保留的消息条数。
     * Math.max(0, n) 防止配置误配负数导致裁剪时算出负索引。
     */
    public int getRetainedMessageCount() {
        return Math.max(0, properties.retainedMessageCount());
    }

    /**
     * 获取触发压缩的 token 阈值。Math.max(1, n) 防止配置误配 0 时变成「每轮都压缩」。
     */
    public int getTokenThreshold() {
        return Math.max(1, properties.triggerTokenThreshold());
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
