package com.link.linkagent.creator.interactive.model;

import java.time.LocalDateTime;
import java.util.List;

public record InteractiveTaskResponse(
        String taskId,
        String sessionId,
        String userId,
        String idea,
        String videoType,
        String status,
        String selectedOptionId,
        String parseStatus,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        List<CreativeIdeaOptionResponse> options
) {
}
