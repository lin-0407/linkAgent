package com.link.linkagent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    @Test
    void shouldRegisterToolsByName() {
        ToolRegistry registry = new ToolRegistry(List.of(new FixedTool("calculator"), new FixedTool("datetime")));

        registry.init();

        assertThat(registry.getTool("calculator")).isNotNull();
        assertThat(registry.getTool("datetime")).isNotNull();
    }

    @Test
    void shouldRejectDuplicateToolName() {
        ToolRegistry registry = new ToolRegistry(List.of(new FixedTool("calculator"), new FixedTool("calculator")));

        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate tool name: calculator");
    }

    @Test
    void shouldRejectBlankToolName() {
        ToolRegistry registry = new ToolRegistry(List.of(new FixedTool(" ")));

        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tool name must not be blank");
    }

    @Test
    void shouldReturnToolsInStableNameOrder() {
        ToolRegistry registry = new ToolRegistry(List.of(new FixedTool("web_search"), new FixedTool("calculator")));

        registry.init();

        assertThat(registry.getAllTools())
                .extracting(Tool::getName)
                .containsExactly("calculator", "web_search");
    }

    private record FixedTool(String name) implements Tool {

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
            return input;
        }
    }
}
