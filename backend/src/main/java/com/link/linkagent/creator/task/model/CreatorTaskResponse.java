package com.link.linkagent.creator.task.model;

import java.time.LocalDateTime;
import java.util.List;

public record CreatorTaskResponse(
        Long id,
        String taskId,
        String userId,
        String taskName,
        String videoType,
        String status,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        List<CreatorMaterialResponse> materials
) {
}
