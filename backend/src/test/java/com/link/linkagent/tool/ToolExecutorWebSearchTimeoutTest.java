package com.link.linkagent.tool;

import com.link.linkagent.core.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutorWebSearchTimeoutTest {

    @Test
    void shouldAllowSearchToFinishWithinItsOwnTimeout() {
        ToolExecutor executor = executorWithSlowTool("web_search");

        assertThat(executor.execute(new ToolCall("web_search", "公开教程")).result())
                .isEqualTo("搜索结果");
    }

    @Test
    void shouldKeepTheGeneralTimeoutForOtherTools() {
        ToolExecutor executor = executorWithSlowTool("other_tool");

        assertThat(executor.execute(new ToolCall("other_tool", "输入")).result())
                .contains("TimeoutException");
    }

    private ToolExecutor executorWithSlowTool(String name) {
        Tool tool = new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "延迟的测试工具"; }
            @Override public String execute(String input) {
                try {
                    Thread.sleep(1400);
                    return "搜索结果";
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        registry.init();
        return new ToolExecutor(registry, new ToolExecutionProperties(1, 0, 4));
    }
}
