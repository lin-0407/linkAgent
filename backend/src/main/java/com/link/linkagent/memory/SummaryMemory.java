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
 * 摘要记忆 —— 将长对话压缩为摘要，解决短期记忆窗口有限导致的上下文丢失问题。
 *
 * <h3>在记忆架构中的位置</h3>
 * 记忆拼接顺序为「长期记忆 → 摘要 → 短期记忆 → 用户输入」。
 * 摘要位于长期记忆之后、短期记忆之前，是早期对话的压缩版——让 LLM 在上下文窗口有限时仍能感知历史要点。
 *
 * <h3>核心设计</h3>
 * <ul>
 *   <li><b>触发式压缩</b>：当短期消息数超过 {@code triggerMessageCount} 阈值时，才调用 LLM 生成摘要。
 *       避免每次对话都跑一次 LLM 调用，节省 Token 成本和延迟。</li>
 *   <li><b>摘要 + 窗口协作</b>：摘要生成后，短期记忆只保留最近 {@code retainedMessageCount} 条消息，
 *       形成「早期对话靠摘要、最近对话靠原文」的分层上下文策略。</li>
 *   <li><b>内存级存储</b>：摘要存储在 {@code ConcurrentHashMap} 中（不落库），服务重启即丢失。
 *       这是有意为之——摘要是短期记忆的衍生品，重启后从零开始重建即可，无需持久化成本。</li>
 *   <li><b>测试兼容性</b>：{@code RuntimeSettingService} 可能为 null（单测构造器场景），
 *      此时回退到 {@code properties.enabled()} 的静态配置值，避免单元测试必须感知运行期设置模块。</li>
 * </ul>
 *
 * <h3>与 AgentExecutor 的协作</h3>
 * 每次 Agent 拿到 Final Answer 后，
 * 会调用 {@link #trySummarize}：若摘要触发，再调用 {@link com.link.linkagent.memory.ShortTermMemory#keepRecentMessages}
 * 裁剪短期记忆，两个操作顺序保证「先压缩后裁剪」的语义正确性。
 */
@Component
public class SummaryMemory {

    private static final Logger log = LoggerFactory.getLogger(SummaryMemory.class);

    /**
     * 摘要记忆的配置参数，包括是否启用、触发消息数阈值、摘要后保留消息数。
     * 通过 Spring {@code @Value} 注入，支持运行期通过配置中心动态调整。
     */
    private final SummaryMemoryProperties properties;

    /**
     * 专门用于生成摘要的 ChatModel 实例。
     * 与主 Agent 使用的 LLM 实例分离，允许为摘要任务配置一个轻量/便宜的模型，
     * 降低摘要压缩的 Token 成本。
     */
    private final ChatModel memorySummaryModel;

    private final PromptService promptService;

    /**
     * 运行期设置服务，支持不重启服务即可开关摘要功能或调整阈值。
     * 为 null 时（单测构造器场景）回退到 {@code properties} 的静态配置值。
     */
    private final RuntimeSettingService runtimeSettingService;

    /**
     * 会话级摘要缓存，按 sessionId 隔离。
     * 使用 ConcurrentHashMap 的原因：
     * <ul>
     *   <li>同一 session 的多次请求可能并发到达，ConcurrentHashMap 保证线程安全</li>
     *   <li>不落库是刻意设计——摘要是短期记忆的衍生品，重启后重建成本低</li>
     * </ul>
     */
    private final Map<String, String> sessionSummaries = new ConcurrentHashMap<>();

    /**
     * 生产环境构造器（由 Spring 自动注入），接收完整的四个依赖。
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
     * 此时回退到 {@code properties.enabled()} 的静态配置值，
     * 避免单元测试必须感知运行期设置模块。
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
     * 尝试生成摘要记忆：消息数超过触发阈值时调用 LLM 压缩并保存。
     *
     * <h3>触发条件</h3>
     * 两个条件必须同时满足：
     * <ol>
     *   <li>摘要功能已启用（由 {@code RuntimeSettingService} 或 {@code properties.enabled()} 决定）</li>
     *   <li>当前会话消息数超过 {@code triggerMessageCount} 阈值（默认 8 条）</li>
     * </ol>
     * 阈值判断使用 {@code >}（严格大于），而非 {@code >=}：
     * 这意味着 9 条消息才触发摘要，8 条暂缓——因为 8 条是默认阈值本身，
     * 若用 {@code >=} 会给 LLM 立即增加摘要调用，对短对话而言是浪费。
     *
     * <h3>方法命名</h3>
     * 方法名用 {@code try} 开头，因为它同时做了「判断 + LLM 调用 + 持久化」三件事，
     * 不只是纯查询——调用方读到 {@code trySummarize} 就能意识到这里有一次 LLM 调用。
     *
     * @param sessionId 会话标识
     * @param messages  当前会话的完整消息列表（包含本轮刚追加的 Human + AI 对）
     * @return true 表示摘要已成功生成并保存；false 表示未触发或生成失败
     */
    public boolean trySummarize(String sessionId, List<MemoryMessage> messages) {
        // 双重条件：功能启用 + 消息数超过阈值。短路求值先判断 enabled 避免多余的 size() 调用。
        if (!isSummaryMemoryEnabled() || messages.size() <= properties.triggerMessageCount()) {
            return false;
        }
        try {
            // 构建摘要提示词（系统提示词 + 当前对话消息），调用 LLM 生成摘要文本
            String prompt = buildPrompt(messages);
            String newMemory = memorySummaryModel.call(prompt);
            saveSummary(sessionId, newMemory);
            return true;
        } catch (Exception e) {
            // 摘要失败不向上抛异常——Agent 主流程不应因摘要失败而中断对话。
            // 最坏情况：用户下次对话时缺少摘要上下文，信息密度稍低，但对话仍可继续。
            log.error("摘要记忆压缩失败，sessionId={}, error={}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 构建摘要生成的完整提示词。
     *
     * <h3>拼接结构</h3>
     * <pre>
     * [summary_memory.system 系统提示词]  ← 告诉 LLM 如何做摘要
     *
     * 当前对话消息如下：
     * Human: ...
     * AI: ...
     * Human: ...
     * </pre>
     *
     * <h3>消息拼接方式</h3>
     * 使用 {@code role + ": " + content} 格式逐条拼接，再通过 {@code reduce} 用换行符连接。
     * 选择 Stream API 而非 for 循环，是因为消息列表可能在多线程下被修改——
     * reduce 是一次性快照消费，比迭代过程中列表被修改更安全。
     *
     * @param messages 当前会话的消息列表（按时间顺序排列）
     * @return 完整的提示词字符串
     */
    private String buildPrompt(List<MemoryMessage> messages) {
        return promptService.get("summary_memory.system") + "\n\n" +
                "当前对话消息如下：\n" +
                messages.stream()
                        .map(message -> message.role() + ": " + message.content())
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("");
    }

    /**
     * 保存摘要到会话级缓存。
     *
     * <h3>防御性处理</h3>
     * <ul>
     *   <li>摘要功能关闭时直接跳过——避免在功能禁用状态下被外部意外写入脏数据</li>
     *   <li>摘要文本为空/空白时跳过——LLM 可能返回空响应，此时不覆盖已有摘要</li>
     *   <li>保存前做 {@code trim()}——去掉 LLM 常见的首尾回撤离换行/空格</li>
     * </ul>
     *
     * @param sessionId 会话标识
     * @param summary   LLM 生成的摘要原始文本
     */
    public void saveSummary(String sessionId, String summary) {
        if (!isSummaryMemoryEnabled() || TextUtil.isBlank(summary)) {
            return;
        }
        sessionSummaries.put(sessionId, summary.trim());
    }

    /**
     * 获取摘要触发后短期记忆应保留的消息数量。
     *
     * <h3>防御性 Math.max(0, n)</h3>
     * 防止配置文件中误配负数导致 {@link ShortTermMemory#keepRecentMessages} 的
     * {@code fromIndex} 计算出错（负数索引在 subList 中会抛出异常）。
     * 零值表示摘要触发后清空所有短期消息——虽然极端，但不会导致崩溃。
     *
     * @return 保留的消息数量，最小值为 0
     */
    public int getRetainedMessageCount() {
        return Math.max(0, properties.retainedMessageCount());
    }

    /**
     * 判断摘要记忆功能是否启用。
     *
     * <h3>决策链</h3>
     * <ol>
     *   <li>若 {@code RuntimeSettingService} 已注入（生产环境）→ 从运行期设置读取，
     *       方便运维在不重启服务的情况下开关摘要功能</li>
     *   <li>若 {@code RuntimeSettingService} 为 null（单测构造器场景）→ 回退到
     *       {@code properties.enabled()} 的静态配置值，避免单元测试必须感知设置模块</li>
     * </ol>
     *
     * <h3>测试兼容设计</h3>
     * 单测使用两个参数的构造器（不含 RuntimeSettingService），此时该字段为 null。
     * 如果此处不加 null 检查而直接调用 {@code runtimeSettingService.isSummaryMemoryEnabled()}，
     * 会导致 NPE。因此采用三元表达式做空安全处理。
     *
     * @return true 表示摘要功能已启用
     */
    private boolean isSummaryMemoryEnabled() {
        return runtimeSettingService == null
                ? properties.enabled()
                : runtimeSettingService.isSummaryMemoryEnabled();
    }
}
