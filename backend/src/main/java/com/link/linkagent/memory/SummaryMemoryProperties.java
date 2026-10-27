package com.link.linkagent.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 摘要记忆配置。
 * <p>
 * 触发条件从「消息条数」改为「请求 token 数」（设计文档 A2）：条数与成本、窗口没有固定关系，
 * 一条粘贴进来的长文稿就可能顶穿上下文，而 20 条短消息可能只占几百 token。
 */
@Component
public class SummaryMemoryProperties {

    /** 事实点写入长期记忆的默认高分线：1-100 口径，作者 2026-09 定稿 80。 */
    private static final int DEFAULT_FACT_IMPORTANCE_THRESHOLD = 80;

    /**
     * 压缩后保留尾部的默认 token 预算。
     * <p>
     * 固定留 2 条消息时，工具执行结果一旦进入会话历史，最后 2 条很容易只留下「最后一个工具结果 + 最终回答」，
     * 把用户问题、本轮前面的工具证据全裁掉；改成按 token 预算保留，尾部体量和每轮请求成本都可预期。
     */
    static final int DEFAULT_RETAINED_TOKEN_BUDGET = 16_000;

    /**
     * 摘要正文的 token 预算默认值。
     * <p>
     * 压缩的目标是「让人能接着干活」，不是无损转写。没有这个上限时，模型遇到几百条工具结果/爬取清单
     * 会试图逐条枚举，输出可以膨胀到 6 万 token 再被供应商截断，进而陷入「截断→复核漏抽→重生成」的循环。
     */
    static final int DEFAULT_MAX_SUMMARY_TOKENS = 12_000;

    /**
     * 摘要生成请求的输出 token 上限默认值。
     * <p>
     * 包含思考 token 的供应商里，这个值必须比「可见摘要预算」宽一些，否则思考会先吃掉名额导致正文被截断；
     * 真正约束正文长度的是 {@link #maxSummaryTokens()}。
     */
    static final int DEFAULT_MAX_OUTPUT_TOKENS = 32_768;

    /**
     * 压缩失败后的重试冷却增量（token）。
     * <p>
     * 一次压缩失败后如果下一轮（只多了几千 token）就重试，会在高阈值附近连续烧多次生成+复核；
     * 等上下文再涨这么多 token 才允许下一次尝试，把失败重试从「每轮一次」降到「隔几轮一次」。
     */
    static final int DEFAULT_RETRY_TOKEN_DELTA = 16_000;

    private final boolean enabled;

    /** 触发压缩的 token 阈值。256k 占 1M 窗口的 25.6%，给输出（上限 384K）和临时内容留足余量。 */
    private final int triggerTokenThreshold;

    /**
     * 压缩后保留的最近消息条数。
     * <p>
     * 只在拿不到 token 计量器（单测最小构造）或 token 预算被配置为 0 时兜底使用；
     * 生产路径优先按 {@link #retainedTokenBudget} 从尾部往前保留整条消息。
     */
    private final int retainedMessageCount;

    /**
     * 压缩后保留尾部的 token 预算，默认 16k，可配置。
     * <p>
     * 为什么按 token 不按条数：一条工具执行结果和历史里一条寒暄的 token 差几十倍，
     * 条数无法表达「尾部保留多少上下文」；按 token 才能让保留量与模型窗口、成本同口径。
     */
    private final int retainedTokenBudget;

    /**
     * 事实点重要性高分线（1-100）。
     * 只有达到这条线的事实点才单独写入长期记忆：压缩后摘要是唯一载体，
     * 高分线是「丢了会明显影响后续协作」的判定边界（设计文档 B3 / D5）。
     */
    private final int factImportanceThreshold;

    /** 摘要正文允许的最大 token 数；0 表示不限制（默认 12k，超过判为不合格、不落库不裁剪）。 */
    private final int maxSummaryTokens;

    /** 摘要生成请求的 max_tokens（含思考）；0 表示不显式设置。 */
    private final int maxOutputTokens;

    /** 压缩失败后的重试冷却增量（token）；0 表示关闭冷却、每轮都可重试。 */
    private final int retryTokenDelta;

    @Autowired
    public SummaryMemoryProperties(
            @Value("${agent.memory.summary.enabled:false}") boolean enabled,
            @Value("${agent.memory.summary.trigger-token-threshold:256000}") int triggerTokenThreshold,
            @Value("${agent.memory.summary.retained-message-count:2}") int retainedMessageCount,
            @Value("${agent.memory.summary.retained-token-budget:16000}") int retainedTokenBudget,
            @Value("${agent.memory.summary.fact-importance-threshold:80}") int factImportanceThreshold,
            @Value("${agent.memory.summary.max-summary-tokens:12000}") int maxSummaryTokens,
            @Value("${agent.memory.summary.max-output-tokens:32768}") int maxOutputTokens,
            @Value("${agent.memory.summary.retry-token-delta:16000}") int retryTokenDelta) {
        this.enabled = enabled;
        this.triggerTokenThreshold = triggerTokenThreshold;
        this.retainedMessageCount = retainedMessageCount;
        // 负值没有语义，按 0 处理：0 表示退回按条数保留，避免裁剪边界算出负下标。
        this.retainedTokenBudget = Math.max(0, retainedTokenBudget);
        // 收敛到 1-100：配置误配 0 会让所有事实点都被判定为高分，误配 1000 则永远不写长期记忆。
        this.factImportanceThreshold = Math.min(100, Math.max(1, factImportanceThreshold));
        this.maxSummaryTokens = Math.max(0, maxSummaryTokens);
        this.maxOutputTokens = Math.max(0, maxOutputTokens);
        this.retryTokenDelta = Math.max(0, retryTokenDelta);
    }

    /** 五参重载供单测与旧调用点使用，新增的三个限制取默认值。 */
    public SummaryMemoryProperties(boolean enabled, int triggerTokenThreshold, int retainedMessageCount,
                                   int retainedTokenBudget, int factImportanceThreshold) {
        this(enabled, triggerTokenThreshold, retainedMessageCount, retainedTokenBudget, factImportanceThreshold,
                DEFAULT_MAX_SUMMARY_TOKENS, DEFAULT_MAX_OUTPUT_TOKENS, DEFAULT_RETRY_TOKEN_DELTA);
    }

    /** 三参重载供单测与旧调用点使用，token 预算与高分线取默认值。 */
    public SummaryMemoryProperties(boolean enabled, int triggerTokenThreshold, int retainedMessageCount) {
        this(enabled, triggerTokenThreshold, retainedMessageCount, DEFAULT_RETAINED_TOKEN_BUDGET,
                DEFAULT_FACT_IMPORTANCE_THRESHOLD);
    }

    public boolean enabled() {
        return enabled;
    }

    public int triggerTokenThreshold() {
        return triggerTokenThreshold;
    }

    public int retainedMessageCount() {
        return retainedMessageCount;
    }

    public int retainedTokenBudget() {
        return retainedTokenBudget;
    }

    public int factImportanceThreshold() {
        return factImportanceThreshold;
    }

    public int maxSummaryTokens() {
        return maxSummaryTokens;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public int retryTokenDelta() {
        return retryTokenDelta;
    }
}
