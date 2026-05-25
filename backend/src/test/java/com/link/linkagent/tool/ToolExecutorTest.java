package com.link.linkagent.tool;

import com.link.linkagent.core.Observation;
import com.link.linkagent.core.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutorTest {

    @Test
    void shouldExecuteRegisteredTool() {
        ToolRegistry registry = new ToolRegistry(List.of(new FixedTool("calculator", "42")));
        registry.init();
        ToolExecutor executor = new ToolExecutor(registry, new ToolExecutionProperties(10, 0));

        Observation observation = executor.execute(new ToolCall("calculator", "6 * 7"));

        assertThat(observation.toolName()).isEqualTo("calculator");
        assertThat(observation.result()).isEqualTo("42");
    }

    @Test
    void shouldReturnErrorWhenToolNotFound() {
        ToolRegistry registry = new ToolRegistry(List.of());
        registry.init();
        ToolExecutor executor = new ToolExecutor(registry, new ToolExecutionProperties(10, 0));
        Observation observation = executor.execute(new ToolCall("unknown", "input"));

        assertThat(observation.toolName()).isEqualTo("unknown");
        assertThat(observation.result()).isEqualTo("Error: tool 'unknown' not found");
    }

    @Test
    void shouldReturnErrorWhenToolThrowsException() {
        ToolRegistry registry = new ToolRegistry(List.of(new BrokenTool("broken")));
        registry.init();
        ToolExecutor executor = new ToolExecutor(registry, new ToolExecutionProperties(10, 0));

        Observation observation = executor.execute(new ToolCall("broken", "input"));

        assertThat(observation.toolName()).isEqualTo("broken");
        assertThat(observation.result()).contains("Error: tool failed");
    }

    @Test
    void shouldReturnErrorWhenToolExecutionTimeout() {
        ToolRegistry registry = new ToolRegistry(List.of(new SlowTool("slow")));
        registry.init();
        ToolExecutor executor = new ToolExecutor(registry, new ToolExecutionProperties(1, 0));

        Observation observation = executor.execute(new ToolCall("slow", "input"));

        assertThat(observation.toolName()).isEqualTo("slow");
        assertThat(observation.result()).contains("Error:");
    }

    @Test
    void shouldRetryWhenToolFailsOnceAndThenSucceeds() {
        ToolRegistry registry = new ToolRegistry(List.of(new FlakyTool("flaky")));
        registry.init();
        ToolExecutor executor = new ToolExecutor(registry, new ToolExecutionProperties(10, 1));

        Observation observation = executor.execute(new ToolCall("flaky", "input"));

        assertThat(observation.toolName()).isEqualTo("flaky");
        assertThat(observation.result()).isEqualTo("recovered");
    }

    @Test
    void shouldReturnErrorWhenRetriesAreExhausted() {
        ToolRegistry registry = new ToolRegistry(List.of(new AlwaysBrokenTool("broken")));
        registry.init();
        ToolExecutor executor = new ToolExecutor(registry, new ToolExecutionProperties(10, 1));

        Observation observation = executor.execute(new ToolCall("broken", "input"));

        assertThat(observation.toolName()).isEqualTo("broken");
        assertThat(observation.result()).contains("Error: tool failed");
    }

    @Test
    void shouldExecuteMultipleToolsInOrder() {
        ToolRegistry registry = new ToolRegistry(List.of(
                new FixedTool("calculator", "42"),
                new FixedTool("datetime", "2026-05-25 10:00:00")));
        registry.init();
        ToolExecutor executor = new ToolExecutor(registry, new ToolExecutionProperties(10, 0));

        List<Observation> observations = executor.executeAll(List.of(
                new ToolCall("calculator", "6 * 7"),
                new ToolCall("datetime", "now")));

        assertThat(observations).extracting(Observation::toolName)
                .containsExactly("calculator", "datetime");
        assertThat(observations).extracting(Observation::result)
                .containsExactly("42", "2026-05-25 10:00:00");
    }

    @Test
    void shouldKeepOtherToolResultsWhenOneToolFails() {
        ToolRegistry registry = new ToolRegistry(List.of(
                new FixedTool("calculator", "42"),
                new AlwaysBrokenTool("broken")));
        registry.init();
        ToolExecutor executor = new ToolExecutor(registry, new ToolExecutionProperties(10, 0));

        List<Observation> observations = executor.executeAll(List.of(
                new ToolCall("calculator", "6 * 7"),
                new ToolCall("broken", "input")));

        assertThat(observations).extracting(Observation::toolName)
                .containsExactly("calculator", "broken");
        assertThat(observations.get(0).result()).isEqualTo("42");
        assertThat(observations.get(1).result()).contains("Error: tool failed");
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

    private static class FlakyTool implements Tool {

        private final String name;
        private final AtomicInteger attempts = new AtomicInteger();

        private FlakyTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "测试偶发失败工具";
        }

        @Override
        public String execute(String input) {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("temporary error");
            }
            return "recovered";
        }
    }

    private record AlwaysBrokenTool(String name) implements Tool {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "测试持续失败工具";
        }

        @Override
        public String execute(String input) {
            throw new IllegalStateException("tool failed");
        }
    }
}
