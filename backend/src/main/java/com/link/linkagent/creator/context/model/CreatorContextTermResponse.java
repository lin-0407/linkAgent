package com.link.linkagent.creator.context.model;

import java.time.LocalDateTime;

public record CreatorContextTermResponse(
        Long id,
        String termId,
        String userId,
        String videoType,
        String term,
        String termType,
        String polarity,
        String sourceType,
        String sourceTaskId,
        String evidenceText,
        Integer weight,
        Integer usageCount,
        Integer acceptCount,
        Integer rejectCount,
        Boolean enabled,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
