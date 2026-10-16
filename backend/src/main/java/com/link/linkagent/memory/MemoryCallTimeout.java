package com.link.linkagent.memory;

import com.link.linkagent.llm.usage.LlmUsageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 记忆压缩链路上模型调用的超时保护。
 *
 * <h3>为什么需要</h3>
 * 摘要生成、摘要复核、事实点抽取都在请求线程的同步路径上，而项目当前没有给 LLM 侧配置 HTTP 超时：
 * provider 卡住时请求线程会一直等，用户既拿不到回答也拿不到错误。作者 2026-09-19 确认：
 * 时间不用抠得很紧，但要有一个 5 分钟的兜底，超过就按调用失败处理。
 *
 * <h3>实现取舍</h3>
 * 用 CompletableFuture + get(timeout) 在调用方侧计时：超时后调用方立刻按失败返回，
 * 底层 HTTP 请求是否被及时中断取决于 HTTP 客户端——这里保证的是「不无限等」，不是「连接立刻释放」。
 * 压缩是低频路径，这个代价可以接受。
 * LlmUsageContext 是 ThreadLocal，调用挪到别的线程前先取出来、在线程内恢复，
 * 否则这次调用的用量统计会丢场景标签。
 */
final class MemoryCallTimeout {

    private static final Logger log = LoggerFactory.getLogger(MemoryCallTimeout.class);

    /** 压缩链路单次模型调用的等待上限：5 分钟。 */
    static final long DEFAULT_TIMEOUT_SECONDS = 300;

    private MemoryCallTimeout() {
    }

    /** 按默认 5 分钟上限执行模型调用。 */
    static <T> T call(String scene, Supplier<T> supplier) throws Exception {
        return call(scene, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS, supplier);
    }

    /** 可指定超时的重载，供单测用短超时验证超时分支。 */
    static <T> T call(String scene, long timeout, TimeUnit unit, Supplier<T> supplier) throws Exception {
        LlmUsageContext usageContext = LlmUsageContext.current();
        MemoryObservationContext.Snapshot observation = MemoryObservationContext.current();
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            if (observation != null) {
                observation.pendingCalls().begin();
            }
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.restore(usageContext);
                 MemoryObservationContext.Scope observationScope = MemoryObservationContext.restore(observation)) {
                return supplier.get();
            } finally {
                if (observation != null) {
                    observation.pendingCalls().end();
                }
            }
        });
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException timeoutException) {
            // cancel 只能取消等待，不会中断已经在跑的 HTTP 请求；日志保留场景名，便于判断是哪一步卡住。
            future.cancel(true);
            log.error("{} 调用超过 {} {} 仍未返回，本次按失败处理", scene, timeout, unit);
            throw timeoutException;
        } catch (InterruptedException interruptedException) {
            // 恢复中断标记：上层继续按失败处理，但不能把线程的中断状态吞掉。
            Thread.currentThread().interrupt();
            throw interruptedException;
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }
}
