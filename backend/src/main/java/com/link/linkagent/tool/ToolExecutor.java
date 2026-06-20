package com.link.linkagent.tool;

import com.link.linkagent.core.Observation;
import com.link.linkagent.core.ToolCall;
import com.link.linkagent.llm.usage.LlmUsageContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 工具执行器。
 * <p>
 * Agent 只关心 Action 能否得到 Observation，工具查找、异常和超时属于工具层边界。
 */
@Component
public class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final ToolExecutionProperties properties;

    public ToolExecutor(ToolRegistry toolRegistry, ToolExecutionProperties properties) {
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    public Observation execute(ToolCall toolCall) {
        return executeInternal(toolCall);
    }

    public List<Observation> executeAll(List<ToolCall> toolCalls) {
        LlmUsageContext usageContext = LlmUsageContext.current();
        List<CompletableFuture<Observation>> futures = toolCalls.stream()
                .map(toolCall -> CompletableFuture.supplyAsync(() -> {
                    try (LlmUsageContext.UsageScope ignored = LlmUsageContext.restore(usageContext)) {
                        return executeInternal(toolCall);
                    }
                }))
                .toList();
        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    private Observation executeInternal(ToolCall toolCall) {
        Tool tool = toolRegistry.getTool(toolCall.name());
        if (tool == null) {
            return new Observation(toolCall.name(), "Error: tool '" + toolCall.name() + "' not found");
        }
        Exception lastException = null;
        for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
            try {
                String result = executeOnce(tool, toolCall);
                return new Observation(toolCall.name(), result);
            } catch (Exception exception) {
                lastException = exception;
            }
        }
        return new Observation(toolCall.name(), "Error: " + resolveErrorMessage(lastException));
    }

    private String executeOnce(Tool tool, ToolCall toolCall) {
        LlmUsageContext usageContext = LlmUsageContext.current();
        return CompletableFuture
                .supplyAsync(() -> {
                    // 工具执行会切到公共线程池，必须显式恢复用量上下文，否则工具内部模型调用无法归属到工作流步骤。
                    try (LlmUsageContext.UsageScope ignored = LlmUsageContext.restore(usageContext)) {
                        return tool.execute(toolCall.arguments());
                    }
                })
                .orTimeout(properties.timeoutSeconds(), TimeUnit.SECONDS)
                .join();
    }

    private String resolveErrorMessage(Exception exception) {
        Throwable cause = exception.getCause();
        if (cause != null && cause.getMessage() != null) {
            return cause.getMessage();
        }
        if (exception.getMessage() != null) {
            return exception.getMessage();
        }
        return exception.getClass().getSimpleName();
    }
}
