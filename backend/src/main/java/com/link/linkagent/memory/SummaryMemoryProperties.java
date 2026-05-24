package com.link.linkagent.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 摘要记忆配置，当前阶段只保留最小必要参数。
 */
@Component
public class SummaryMemoryProperties {

    private final boolean enabled;
    private final int triggerMessageCount;
    private final int retainedMessageCount;

    public SummaryMemoryProperties(
            @Value("${agent.memory.summary.enabled:false}") boolean enabled,
            @Value("${agent.memory.summary.trigger-message-count:8}") int triggerMessageCount,
            @Value("${agent.memory.summary.retained-message-count:2}") int retainedMessageCount) {
        this.enabled = enabled;
        this.triggerMessageCount = triggerMessageCount;
        this.retainedMessageCount = retainedMessageCount;
    }

    public boolean enabled() {
        return enabled;
    }

    public int triggerMessageCount() {
        return triggerMessageCount;
    }

    public int retainedMessageCount() {
        return retainedMessageCount;
    }
}
