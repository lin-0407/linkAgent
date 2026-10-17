package com.link.linkagent.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 工具执行配置。
 * <p>
 * 将超时时间独立成配置，是为了后续不同工具接入外部服务时，可以先用统一保护兜住主链路。
 */
@Component
public class ToolExecutionProperties {

    private final long timeoutSeconds;
    private final int maxRetries;
    private final long webSearchTimeoutSeconds;

    @Autowired
    public ToolExecutionProperties(@Value("${agent.tool.execution.timeout-seconds:10}") long timeoutSeconds,
                                   @Value("${agent.tool.execution.max-retries:0}") int maxRetries,
                                   @Value("${agent.tool.web-search.timeout-seconds:60}") long webSearchTimeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        this.maxRetries = maxRetries;
        this.webSearchTimeoutSeconds = webSearchTimeoutSeconds;
    }

    public ToolExecutionProperties(long timeoutSeconds, int maxRetries) {
        this(timeoutSeconds, maxRetries, timeoutSeconds);
    }

    public long timeoutSeconds() {
        return Math.max(1, timeoutSeconds);
    }

    public long timeoutSeconds(String toolName) {
        // 搜索还要抓取多个页面并压缩正文，统一的 10 秒会在正常执行完成前返回超时。
        return "web_search".equals(toolName) || "platform_query".equals(toolName)
                ? Math.max(timeoutSeconds(), webSearchTimeoutSeconds) : timeoutSeconds();
    }

    public int maxRetries() {
        return Math.max(0, maxRetries);
    }
}
