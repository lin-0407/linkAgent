package com.link.linkagent.creator.bilibili.model;

/**
 * 任务进入 BV 绑定前的只读就绪状态。
 * 阻塞原因直接复用服务端发布后门禁文案，确保页面提示与真正提交时的 409 原因一致。
 */
public record PostPublishReadinessResponse(
        String taskId,
        boolean ready,
        String blockingReason
) {
}
