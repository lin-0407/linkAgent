package com.link.linkagent.memory;

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

    private final boolean enabled;

    /** 触发压缩的 token 阈值。256k 占 1M 窗口的 25.6%，给输出（上限 384K）和临时内容留足余量。 */
    private final int triggerTokenThreshold;

    /** 压缩后保留的最近消息条数，其余消息由摘要承载。 */
    private final int retainedMessageCount;

    public SummaryMemoryProperties(
            @Value("${agent.memory.summary.enabled:false}") boolean enabled,
            @Value("${agent.memory.summary.trigger-token-threshold:256000}") int triggerTokenThreshold,
            @Value("${agent.memory.summary.retained-message-count:2}") int retainedMessageCount) {
        this.enabled = enabled;
        this.triggerTokenThreshold = triggerTokenThreshold;
        this.retainedMessageCount = retainedMessageCount;
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
}
