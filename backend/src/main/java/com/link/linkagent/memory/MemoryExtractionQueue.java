package com.link.linkagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 长期记忆提取队列（设计文档 C6 / C7 / D9）。
 * <p>
 * <b>为什么用 Redis List 而不是 JVM 队列</b>：后端一重启，JVM 里的排队任务全丢，游标又不会推进，
 * 那批消息会一直没人提取；放在 Redis 里，重启后消费者继续从队列取。
 * <p>
 * <b>为什么是单键 + 载荷带 userId，而不是 {@code queue:{userId}} 分键</b>：
 * 需求要的是「同一用户的任务串行」，单线程消费者天然满足这一点；按用户分键还要额外维护「有哪些活跃用户」
 * 才能在启动时知道该盯哪些 key。单用户部署下两种写法完全等价，因此本轮用单键，偏差记在步骤 7 文档里。
 * <p>
 * <b>为什么入队与取任务都不抛异常</b>：记忆是增强能力，Redis 抖动不应该把对话链路或消费者线程弄崩；
 * 入队失败由调用方降级（压缩链路退回直接写库），取任务失败当作「本轮没拿到任务」继续轮询。
 */
@Component
public class MemoryExtractionQueue {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionQueue.class);

    /** 队列键：右进左出（RPUSH + BLPOP），保证先进先出。 */
    static final String QUEUE_KEY = "link-agent:memory:extract:queue";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public MemoryExtractionQueue(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 任务入队。
     *
     * @return true=已入队；false=Redis 不可用或序列化失败，调用方需要走降级路径
     */
    public boolean enqueue(MemoryExtractionTask task) {
        if (task == null || task.userId() == null) {
            return false;
        }
        try {
            redisTemplate.opsForList().rightPush(QUEUE_KEY, objectMapper.writeValueAsString(task));
            log.info("长期记忆任务已入队，userId={}, 消息范围={}-{}, 预抽候选={}",
                    task.userId(), task.fromMessageId(), task.toMessageId(), task.facts().size());
            return true;
        } catch (Exception exception) {
            log.warn("长期记忆任务入队失败，本批将由调用方降级处理，userId={}, error={}",
                    task.userId(), exception.getMessage());
            return false;
        }
    }

    /**
     * 阻塞取一个任务。
     * <p>
     * 用 BLPOP 而不是「先查长度再取」：单消费者时两者等价，但阻塞等待能避免空轮询，任务到达即刻开始处理。
     *
     * @param timeoutSeconds 阻塞超时；到点返回空，让消费者线程有机会检查停止标记
     * @return 取到的任务；超时、队列为空或 Redis 不可用时返回空
     */
    public Optional<MemoryExtractionTask> poll(int timeoutSeconds) {
        try {
            String payload = redisTemplate.opsForList()
                    .leftPop(QUEUE_KEY, Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (payload == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(payload, MemoryExtractionTask.class));
        } catch (Exception exception) {
            log.warn("长期记忆任务出队失败，本轮跳过，error={}", exception.getMessage());
            return Optional.empty();
        }
    }
}
