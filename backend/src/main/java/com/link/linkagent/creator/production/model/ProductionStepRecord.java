package com.link.linkagent.creator.production.model;

import java.time.LocalDateTime;

/** creator_production_step 的持久化记录。 */
public record ProductionStepRecord(
        Long id,
        String stepId,
        String planId,
        String taskId,
        Integer sequenceNo,
        String phase,
        String stepName,
        String objective,
        String prerequisites,
        String operationsJson,
        String toolRefs,
        String expectedOutputs,
        String acceptanceCriteria,
        String difficulty,
        Boolean requiredFlag,
        String status,
        Long rowVersion,
        String skipReason,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
