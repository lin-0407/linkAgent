package com.link.linkagent.dto;

import java.time.LocalDateTime;

public record LongTermMemoryResponse(
        Long id,
        String userId,
        String memoryKey,
        String content,
        String sourceSessionId,
        String embeddingId,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
