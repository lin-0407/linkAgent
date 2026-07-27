package com.link.linkagent.creator.workflow.model;

/**
 * 想法对齐和发布方案共用的用户上下文。
 * sourceContext 给主 Agent 和审查 Agent 使用，包含完整材料；planContext 给发布方案生成器使用，
 * 不重复包含任务材料，避免同一份内容在一次模型调用中出现两遍。
 */
public record CreatorIntentAlignmentContext(
        String sourceContext,
        String planContext
) {
}
