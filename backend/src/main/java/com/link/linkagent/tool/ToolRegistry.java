package com.link.linkagent.tool;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool 注册中心。
 * <p>
 * 通过构造器注入所有 Tool 实现类，@PostConstruct 注册到 Map。
 */
@Component
public class ToolRegistry {

    private final List<Tool> tools;
    private final Map<String, Tool> toolMap = new HashMap<>();

    public ToolRegistry(List<Tool> tools) {
        this.tools = tools;
    }

    @PostConstruct
    void init() {
        for (Tool tool : tools) {
            toolMap.put(tool.getName(), tool);
        }
    }

    public Tool getTool(String name) {
        return toolMap.get(name);
    }

    public Collection<Tool> getAllTools() {
        return Collections.unmodifiableCollection(tools);
    }
}
