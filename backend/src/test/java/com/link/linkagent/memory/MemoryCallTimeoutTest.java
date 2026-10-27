package com.link.linkagent.memory;

import com.link.linkagent.llm.DeepSeekThinkingOptionsFactory;
import com.link.linkagent.llm.DeepSeekThinkingProperties;
import com.link.linkagent.llm.usage.LlmUsageContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryCallTimeoutTest {

    @Test
    void shouldReturnValueWhenCallCompletes() throws Exception {
        assertThat(MemoryCallTimeout.call("测试", () -> "ok")).isEqualTo("ok");
    }

    @Test
    void shouldThrowTimeoutWhenCallExceedsLimit() {
        // 用短超时验证兜底分支：生产默认 5 分钟，用例不能用真实默认值等待。
        // 用 parkNanos 而不是 Thread.sleep：Supplier#get 不允许抛受检异常，sleep 的 InterruptedException 编译不过。
        assertThatThrownBy(() -> MemoryCallTimeout.call("测试", 50, TimeUnit.MILLISECONDS, () -> {
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));
            return "late";
        })).isInstanceOf(TimeoutException.class);
    }

    @Test
    void shouldRethrowOriginalRuntimeException() {
        // 调用方按业务异常处理（复核失败 / 抽取失败），不能被包装成别的类型
        assertThatThrownBy(() -> MemoryCallTimeout.call("测试", () -> {
            throw new IllegalStateException("模型不可用");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("模型不可用");
    }

    @Test
    void shouldKeepLowEffortWhenMemoryCallMovesToAnotherThread() throws Exception {
        DeepSeekThinkingProperties properties = new DeepSeekThinkingProperties();
        properties.setDefaultModel("deepseek-v4-flash");
        DeepSeekThinkingOptionsFactory factory = new DeepSeekThinkingOptionsFactory(properties, null);
        try (var ignored = LlmUsageContext.scene("长期记忆抽取")) {
            String effort = MemoryCallTimeout.call("长期记忆抽取",
                    () -> factory.optionsForDefaultModel().getReasoningEffort());
            assertThat(effort).isEqualTo("low");
        }
        assertThat(factory.optionsForDefaultModel().getReasoningEffort()).isEqualTo("max");
    }
}
