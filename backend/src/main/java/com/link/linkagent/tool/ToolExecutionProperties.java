package com.link.linkagent.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 工具执行配置。
 * <p>
 * 将超时时间独立成配置，是为了后续不同工具接入外部服务时，可以先用统一保护兜住主链路。
 */
@Component
public class ToolExecutionProperties {

    private final long timeoutSeconds;

    public ToolExecutionProperties(@Value("${agent.tool.execution.timeout-seconds:10}") long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public long timeoutSeconds() {
        return Math.max(1, timeoutSeconds);
    }
}
