package com.link.linkagent.tool;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool 注册中心。
 * <p>
 * 在启动期完成工具校验，是为了让工具生态的问题尽早暴露，避免 Agent 运行到一半才发现工具名冲突。
 */
@Component
public class ToolRegistry {

    private final List<Tool> tools;
    private final Map<String, Tool> toolMap = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> tools) {
        this.tools = tools;
    }

    @PostConstruct
    void init() {
        tools.stream()
                // 工具列表进入系统提示词，稳定排序能让日志、测试和问题复现更容易。
                .sorted(Comparator.comparing(tool -> resolveToolName(tool)))
                .forEach(tool -> {
                    String toolName = resolveToolName(tool);
                    if (toolMap.containsKey(toolName)) {
                        throw new IllegalStateException("Duplicate tool name: " + toolName);
                    }
                    toolMap.put(toolName, tool);
                });
    }

    public Tool getTool(String name) {
        return toolMap.get(name);
    }

    public Collection<Tool> getAllTools() {
        return Collections.unmodifiableCollection(toolMap.values());
    }

    private String resolveToolName(Tool tool) {
        String toolName = tool.getName();
        if (!StringUtils.hasText(toolName)) {
            throw new IllegalStateException("Tool name must not be blank: " + tool.getClass().getName());
        }
        return toolName.trim();
    }
}
