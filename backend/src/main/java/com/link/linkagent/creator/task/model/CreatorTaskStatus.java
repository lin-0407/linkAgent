package com.link.linkagent.creator.task.model;

/**
 * 创作任务状态。
 * 阶段 4.1 先保留最小状态集合，避免一开始就设计复杂状态机。
 */
public enum CreatorTaskStatus {

    DRAFT,
    PRE_PUBLISH_ANALYZED,
    FEEDBACK_ANALYZED,
    ANALYZED,
    ARCHIVED
}
