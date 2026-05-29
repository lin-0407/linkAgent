package com.link.linkagent.creator.workflow.model;

/**
 * 工作流步骤状态。
 * 步骤状态比会话状态更细，用于后续失败回放时定位卡在哪一个业务节点。
 */
public enum CreatorWorkflowStepStatus {
    /**
     * 步骤已创建，还没有实际执行。
     */
    PENDING,

    /**
     * 步骤正在执行。
     */
    RUNNING,

    /**
     * 步骤执行成功。
     */
    SUCCESS,

    /**
     * 步骤执行失败。
     */
    FAILED
}
