package com.link.linkagent.tool;

import com.link.linkagent.core.Observation;
import com.link.linkagent.core.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutorTest {

    @Test
    void shouldExecuteRegisteredTool() {
        ToolRegistry registry = new ToolRegistry(List.of(new FixedTool("calculator", "42")));
        registry.init();
        ToolExecutor executor = new ToolExecutor(registry, new ToolExecutionProperties(10));

        Observation observation = executor.execute(new ToolCall("calculator", "6 * 7"));

        assertThat(observation.toolName()).isEqualTo("calculator");
        assertThat(observation.result()).isEqualTo("42");
    }

    @Test
    void shouldReturnErrorWhenToolNotFound() {
        ToolRegistry registry = new ToolRegistry(List.of());
        registry.init();
        ToolExecutor executor = new ToolExecutor(registry, new ToolExecutionProperties(10));

        Observation observation = executor.execute(new ToolCall("unknown", "input"));

        assertThat(observation.toolName()).isEqualTo("unknown");
        assertThat(observation.result()).isEqualTo("Error: tool 'unknown' not found");
    }

    @Test
    void shouldReturnErrorWhenToolThrowsException() {
        ToolRegistry registry = new ToolRegistry(List.of(new BrokenTool("broken")));
        registry.init();
        ToolExecutor executor = new ToolExecutor(registry, new ToolExecutionProperties(10));

        Observation observation = executor.execute(new ToolCall("broken", "input"));

        assertThat(observation.toolName()).isEqualTo("broken");
        assertThat(observation.result()).contains("Error: tool failed");
    }

    @Test
    void shouldReturnErrorWhenToolExecutionTimeout() {
        ToolRegistry registry = new ToolRegistry(List.of(new SlowTool("slow")));
        registry.init();
        ToolExecutor executor = new ToolExecutor(registry, new ToolExecutionProperties(1));

        Observation observation = executor.execute(new ToolCall("slow", "input"));

        assertThat(observation.toolName()).isEqualTo("slow");
        assertThat(observation.result()).contains("Error:");
    }

    private record FixedTool(String name, String result) implements Tool {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "测试工具";
        }

        @Override
        public String execute(String input) {
            return result;
        }
    }

    private record BrokenTool(String name) implements Tool {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "测试异常工具";
        }

        @Override
        public String execute(String input) {
            throw new IllegalStateException("tool failed");
        }
    }

    private record SlowTool(String name) implements Tool {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "测试超时工具";
        }

        @Override
        public String execute(String input) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return "too late";
        }
    }
}
