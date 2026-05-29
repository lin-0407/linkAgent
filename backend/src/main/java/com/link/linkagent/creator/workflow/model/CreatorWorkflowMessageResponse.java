package com.link.linkagent.creator.workflow.model;

import java.time.LocalDateTime;

public record CreatorWorkflowMessageResponse(
        Long id,
        String messageId,
        String sessionId,
        String role,
        String content,
        String contentType,
        String detailRefType,
        String detailRefId,
        Integer sequenceNo,
        LocalDateTime createTime
) {
}
