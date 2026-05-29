package com.link.linkagent.creator.workflow.model;

/**
 * 工作流 SSE 事件类型。
 * 字符串值需要保持稳定，前端 EventSource 会按这些名称监听事件。
 */
public enum CreatorWorkflowEventType {
    SESSION_STATUS("session_status"),
    MESSAGE_CREATED("message_created"),
    STEP_STARTED("step_started"),
    STEP_COMPLETED("step_completed"),
    STEP_FAILED("step_failed"),
    RESULT_READY("result_ready"),
    HEARTBEAT("heartbeat");

    private final String eventName;

    CreatorWorkflowEventType(String eventName) {
        this.eventName = eventName;
    }

    public String eventName() {
        return eventName;
    }
}
