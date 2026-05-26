package com.link.linkagent.creator.task.model;

import java.time.LocalDateTime;

public record CreatorMaterialResponse(
        Long id,
        String materialType,
        String content,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
