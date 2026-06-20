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
    private final String workflowSessionId;
    private final String workflowStepId;
    private final String workflowStepName;
    private final String workflowStage;

    private LlmUsageContext(String taskId,
                            String traceId,
                            String requestId,
                            String scene,
                            String workflowSessionId,
                            String workflowStepId,
                            String workflowStepName,
                            String workflowStage) {
        this.taskId = trimToNull(taskId);
        this.traceId = trimToNull(traceId);
        this.requestId = trimToNull(requestId);
        this.scene = trimToNull(scene);
        this.workflowSessionId = trimToNull(workflowSessionId);
        this.workflowStepId = trimToNull(workflowStepId);
        this.workflowStepName = trimToNull(workflowStepName);
        this.workflowStage = trimToNull(workflowStage);
    }

    public static LlmUsageContext current() {
        return CURRENT.get();
    }

    public static UsageScope restore(LlmUsageContext context) {
        LlmUsageContext previous = CURRENT.get();
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
        return new UsageScope(previous);
    }

    public static UsageScope open(String taskId, String scene) {
        LlmUsageContext previous = CURRENT.get();
        String traceId = previous == null || previous.traceId == null ? UUID.randomUUID().toString() : previous.traceId;
        String requestId = previous == null || previous.requestId == null ? UUID.randomUUID().toString() : previous.requestId;
        String resolvedScene = previous != null && previous.workflowStepId != null && previous.scene != null
                ? previous.scene
                : scene;
        LlmUsageContext next = new LlmUsageContext(
                taskId,
                traceId,
                requestId,
                resolvedScene,
                previous == null ? null : previous.workflowSessionId,
                previous == null ? null : previous.workflowStepId,
                previous == null ? null : previous.workflowStepName,
                previous == null ? null : previous.workflowStage
        );
        CURRENT.set(next);
        return new UsageScope(previous);
    }

    public static UsageScope scene(String scene) {
        LlmUsageContext previous = CURRENT.get();
        if (previous == null) {
            CURRENT.set(new LlmUsageContext(null, UUID.randomUUID().toString(), UUID.randomUUID().toString(), scene,
                    null, null, null, null));
        } else {
            CURRENT.set(new LlmUsageContext(previous.taskId, previous.traceId, previous.requestId, scene,
                    previous.workflowSessionId, previous.workflowStepId, previous.workflowStepName, previous.workflowStage));
        }
        return new UsageScope(previous);
    }

    public static UsageScope openWorkflowStep(String taskId,
                                              String workflowSessionId,
                                              String workflowStepId,
                                              String workflowStepName,
                                              String workflowStage,
                                              String scene) {
        LlmUsageContext previous = CURRENT.get();
        String traceId = previous == null || previous.traceId == null ? UUID.randomUUID().toString() : previous.traceId;
        String requestId = previous == null || previous.requestId == null ? UUID.randomUUID().toString() : previous.requestId;
        LlmUsageContext next = new LlmUsageContext(
                taskId,
                traceId,
                requestId,
                scene,
                workflowSessionId,
                workflowStepId,
                workflowStepName,
                workflowStage
        );
        CURRENT.set(next);
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

    public String workflowSessionId() {
        return workflowSessionId;
    }

    public String workflowStepId() {
        return workflowStepId;
    }

    public String workflowStepName() {
        return workflowStepName;
    }

    public String workflowStage() {
        return workflowStage;
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
