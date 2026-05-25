package com.link.linkagent.tool.mcp;

import com.link.linkagent.tool.Tool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

/**
 * Spring AI ToolCallback 适配器。
 * <p>
 * MCP 客户端发现到的工具会以 ToolCallback 形式暴露，这里将它转换成项目内部 Tool，
 * 是为了让本地工具和 MCP 工具复用同一套注册、超时、重试和批量执行边界。
 */
public class SpringAiToolCallbackAdapter implements Tool {

    private final ToolCallback toolCallback;
    private final ToolDefinition toolDefinition;

    public SpringAiToolCallbackAdapter(ToolCallback toolCallback) {
        this.toolCallback = toolCallback;
        this.toolDefinition = toolCallback.getToolDefinition();
    }

    @Override
    public String getName() {
        return toolDefinition.name();
    }

    @Override
    public String getDescription() {
        String description = toolDefinition.description();
        String inputSchema = toolDefinition.inputSchema();
        if (!StringUtils.hasText(inputSchema)) {
            return description;
        }
        return description + " Input schema: " + inputSchema;
    }

    @Override
    public String execute(String input) {
        return toolCallback.call(input);
    }
}
