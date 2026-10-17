package com.link.linkagent.memory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 长期记忆提取消费者 —— 单线程、串行处理队列任务（设计文档 C6 / C8 / D9）。
 *
 * <h3>为什么是一条线程而不是线程池</h3>
 * 需求是「每个用户的任务串行」：单线程天然满足，不需要再写「先检查是否正在提取」这类判断
 * （检查与动手之间有空隙，多消费者时挡不住并发）。真正的代价是同一用户的多个任务要排队，
 * 但提取本身是异步的，用户感知不到。
 *
 * <h3>为什么用独立线程而不是 {@code @Scheduled}</h3>
 * 提取一次可能跑几分钟（两次模型调用 + 5 分钟超时兜底）。Spring 默认调度器只有一条线程，
 * 挂在上面会连带拖慢媒体预处理等其它定时任务，所以这里自己起一条守护线程。
 *
 * <h3>关闭与重启</h3>
 * {@link PreDestroy} 只置停止标记并中断阻塞等待，让轮询超时后自然退出；
 * 处理中的任务不打断——它要么跑完推进游标，要么失败留在原地等下一次触发（游标不会推进）。
 */
@Component
@ConditionalOnProperty(prefix = "agent.memory.extract", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class MemoryExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionWorker.class);

    /** 队列阻塞等待的秒数：到点返回一次，让线程有机会检查停止标记。 */
    static final int DEFAULT_POLL_TIMEOUT_SECONDS = 1;

    private final MemoryExtractionQueue queue;
    private final LongTermMemoryExtractionCoordinator coordinator;
    private final int pollTimeoutSeconds;

    private volatile boolean running;
    private Thread workerThread;

    /**
     * 生产构造器。
     * <p>
     * 必须显式标 {@code @Autowired}：类里有两个构造器时 Spring 不再自动选，找不到默认构造器会直接启动失败
     * （2026-09-20 实测：test profile 把消费者关掉，上下文测试没覆盖到这条，只有真正启动应用才会暴露）。
     */
    @Autowired
    public MemoryExtractionWorker(MemoryExtractionQueue queue, LongTermMemoryExtractionCoordinator coordinator,
                                  @Value("${agent.memory.extract.worker-poll-timeout-seconds:1}") int pollTimeoutSeconds) {
        this.queue = queue;
        this.coordinator = coordinator;
        this.pollTimeoutSeconds = Math.max(1, pollTimeoutSeconds);
    }

    /** 单测构造：轮询超时取默认值。 */
    public MemoryExtractionWorker(MemoryExtractionQueue queue, LongTermMemoryExtractionCoordinator coordinator) {
        this(queue, coordinator, DEFAULT_POLL_TIMEOUT_SECONDS);
    }

    @PostConstruct
    void start() {
        if (running) {
            return;
        }
        running = true;
        workerThread = new Thread(this::loop, "long-term-memory-extract-worker");
        // 守护线程：即使停止逻辑没跑到，也不阻塞 JVM 退出。
        workerThread.setDaemon(true);
        workerThread.start();
        log.info("长期记忆提取消费者已启动，队列={}", MemoryExtractionQueue.QUEUE_KEY);
    }

    @PreDestroy
    void stop() {
        if (!running) {
            return;
        }
        running = false;
        Thread thread = workerThread;
        if (thread != null) {
            // 中断只是为了尽快结束正在阻塞的 BLPOP；处理中的任务不会被中断（模型调用不会响应中断）。
            thread.interrupt();
        }
        log.info("长期记忆提取消费者已请求停止");
    }

    private void loop() {
        while (running) {
            pollAndProcessOnce();
        }
        log.info("长期记忆提取消费者已停止");
    }

    /**
     * 取一个任务并处理（无任务时阻塞到轮询超时）。
     * <p>
     * 任何异常都在这里收口：消费线程必须活着，一个任务处理不了不应该让整条链路停摆。
     *
     * @return true=本轮正常（含无任务），false=本轮出现异常
     */
    boolean pollAndProcessOnce() {
        try {
            coordinator.consumeQueuedTask(queue, pollTimeoutSeconds);
            return true;
        } catch (Exception exception) {
            log.error("长期记忆消费循环出现异常，继续下一轮", exception);
            return false;
        }
    }

    boolean isRunning() {
        return running;
    }
}
