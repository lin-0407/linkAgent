package com.link.linkagent.creator.task.model;

import java.time.LocalDateTime;

public record CreatorTaskSummaryResponse(
        Long id,
        String taskId,
        String userId,
        String taskName,
        String status,
        Integer materialCount,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
