package com.link.linkagent.creator.media.preflight.model;

import java.time.LocalDateTime;

/** 发布前试映步骤持久化记录。 */
public record PreflightStepRecord(
        Long id,
        String stepId,
        String reviewId,
        String stepType,
        Integer sequenceNo,
        String status,
        Integer attemptCount,
        String inputFingerprint,
        String outputRef,
        String providerTaskId,
        String errorCode,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
