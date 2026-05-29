package com.link.linkagent.creator.workflow.model;

import java.time.LocalDateTime;

public record CreatorWorkflowEventResponse(
        String eventId,
        String sessionId,
        String taskId,
        String eventType,
        Integer sequenceNo,
        Object payload,
        LocalDateTime createTime
) {
}
