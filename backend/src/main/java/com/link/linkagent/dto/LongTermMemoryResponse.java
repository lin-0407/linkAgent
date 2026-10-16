package com.link.linkagent.dto;

import java.time.LocalDateTime;

public record LongTermMemoryResponse(
        Long id,
        String userId,
        String memoryKey,
        String content,
        // 重要性分数（1-100，0=未打分）：模型抽取的记忆都有值（攒批抽取 / 压缩时的高分事实点），
    // 管理页手工创建与老数据是 0；前端记忆管理页展示用。
        Integer importance,
        String sourceSessionId,
        String embeddingId,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
