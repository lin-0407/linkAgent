package com.link.linkagent.tool;

import com.link.linkagent.core.Observation;
import com.link.linkagent.core.ToolCall;
import org.springframework.stereotype.Component;

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
        Tool tool = toolRegistry.getTool(toolCall.name());
        if (tool == null) {
            return new Observation(toolCall.name(), "Error: tool '" + toolCall.name() + "' not found");
        }
        try {
            String result = CompletableFuture
                    .supplyAsync(() -> tool.execute(toolCall.arguments()))
                    .orTimeout(properties.timeoutSeconds(), TimeUnit.SECONDS)
                    .join();
            return new Observation(toolCall.name(), result);
        } catch (Exception exception) {
            return new Observation(toolCall.name(), "Error: " + resolveErrorMessage(exception));
        }
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
