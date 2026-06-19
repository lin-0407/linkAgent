package com.link.linkagent.llm.usage;

import java.util.UUID;

/**
 * 模型调用上下文。
 * 用 ThreadLocal 是因为一次 HTTP 请求内的文本模型、Embedding、rerank 往往分散在多层服务里，
 * 显式把 taskId / scene 层层传参会污染业务方法签名；ThreadLocal 只保存追踪元数据，不保存业务数据。
 */
public final class LlmUsageContext {

    private static final ThreadLocal<LlmUsageContext> CURRENT = new ThreadLocal<>();

    private final String taskId;
    private final String traceId;
    private final String requestId;
    private final String scene;

    private LlmUsageContext(String taskId, String traceId, String requestId, String scene) {
        this.taskId = trimToNull(taskId);
        this.traceId = trimToNull(traceId);
        this.requestId = trimToNull(requestId);
        this.scene = trimToNull(scene);
    }

    public static LlmUsageContext current() {
        return CURRENT.get();
    }

    public static UsageScope open(String taskId, String scene) {
        LlmUsageContext previous = CURRENT.get();
        String traceId = previous == null || previous.traceId == null ? UUID.randomUUID().toString() : previous.traceId;
        String requestId = previous == null || previous.requestId == null ? UUID.randomUUID().toString() : previous.requestId;
        LlmUsageContext next = new LlmUsageContext(taskId, traceId, requestId, scene);
        CURRENT.set(next);
        return new UsageScope(previous);
    }

    public static UsageScope scene(String scene) {
        LlmUsageContext previous = CURRENT.get();
        if (previous == null) {
            CURRENT.set(new LlmUsageContext(null, UUID.randomUUID().toString(), UUID.randomUUID().toString(), scene));
        } else {
            CURRENT.set(new LlmUsageContext(previous.taskId, previous.traceId, previous.requestId, scene));
        }
        return new UsageScope(previous);
    }

    public String taskId() {
        return taskId;
    }

    public String traceId() {
        return traceId;
    }

    public String requestId() {
        return requestId;
    }

    public String scene() {
        return scene;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public static final class UsageScope implements AutoCloseable {

        private final LlmUsageContext previous;
        private boolean closed;

        private UsageScope(LlmUsageContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
