package com.link.linkagent.creator.workflow.model;

import java.time.LocalDateTime;
import java.util.List;

public record CreatorWorkflowSessionResponse(
        Long id,
        String sessionId,
        String taskId,
        String stage,
        String status,
        String userId,
        String confirmedResultId,
        Integer planGenerationCount,
        String errorMessage,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        List<CreatorWorkflowMessageResponse> messages
) {
}
