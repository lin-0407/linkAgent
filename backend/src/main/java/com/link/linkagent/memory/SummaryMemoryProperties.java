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

    private final boolean enabled;

    /** 触发压缩的 token 阈值。256k 占 1M 窗口的 25.6%，给输出（上限 384K）和临时内容留足余量。 */
    private final int triggerTokenThreshold;

    /** 压缩后保留的最近消息条数，其余消息由摘要承载。 */
    private final int retainedMessageCount;

    /**
     * 事实点重要性高分线（1-100）。
     * 只有达到这条线的事实点才单独写入长期记忆：压缩后摘要是唯一载体，
     * 高分线是「丢了会明显影响后续协作」的判定边界（设计文档 B3 / D5）。
     */
    private final int factImportanceThreshold;

    @Autowired
    public SummaryMemoryProperties(
            @Value("${agent.memory.summary.enabled:false}") boolean enabled,
            @Value("${agent.memory.summary.trigger-token-threshold:256000}") int triggerTokenThreshold,
            @Value("${agent.memory.summary.retained-message-count:2}") int retainedMessageCount,
            @Value("${agent.memory.summary.fact-importance-threshold:80}") int factImportanceThreshold) {
        this.enabled = enabled;
        this.triggerTokenThreshold = triggerTokenThreshold;
        this.retainedMessageCount = retainedMessageCount;
        // 收敛到 1-100：配置误配 0 会让所有事实点都被判定为高分，误配 1000 则永远不写长期记忆。
        this.factImportanceThreshold = Math.min(100, Math.max(1, factImportanceThreshold));
    }

    /** 三参重载供单测与旧调用点使用，高分线取默认 80。 */
    public SummaryMemoryProperties(boolean enabled, int triggerTokenThreshold, int retainedMessageCount) {
        this(enabled, triggerTokenThreshold, retainedMessageCount, DEFAULT_FACT_IMPORTANCE_THRESHOLD);
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

    public int factImportanceThreshold() {
        return factImportanceThreshold;
    }
}
