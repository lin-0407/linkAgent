package com.link.linkagent.core;

import com.link.linkagent.tool.Tool;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * 工具清单提示词格式化器。
 * <p>
 * ReAct、PaE Planner 和多 Agent Planner 都需要同一份工具描述，集中处理能避免不同内核看到的工具语义不一致。
 */
public final class AgentToolPromptFormatter {

    private AgentToolPromptFormatter() {
    }

    public static String format(Collection<Tool> tools) {
        return tools.stream()
                .map(t -> "- " + t.getName() + ": " + t.getDescription())
                .collect(Collectors.joining("\n"));
    }
}
