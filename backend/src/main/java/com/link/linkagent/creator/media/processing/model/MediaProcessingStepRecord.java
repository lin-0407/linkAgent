package com.link.linkagent.creator.media.processing.model;

import java.time.LocalDateTime;

/**
 * creator_media_processing_step 数据库记录。
 */
public record MediaProcessingStepRecord(
        Long id,
        String stepId,
        String jobId,
        String stepCode,
        String stepName,
        Integer sequenceNo,
        String status,
        Integer progressPercent,
        String outputSummary,
        String failureMessage,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
