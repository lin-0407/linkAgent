package com.link.linkagent.creator.production.model;

import java.time.LocalDateTime;

/** creator_production_plan 的持久化记录。JSON 字段按字符串保存，避免在 Mapper 层绑定业务对象。 */
public record ProductionPlanRecord(
        Long id,
        String planId,
        String taskId,
        String ownerId,
        Integer planVersion,
        String videoCategory,
        String productionMethod,
        String targetAudience,
        String corePromise,
        Long targetDurationMs,
        String availableAssets,
        String constraintsJson,
        String toolPreferences,
        String sourceSnapshot,
        String planTitle,
        String positioningSummary,
        String status,
        String rawOutput,
        String promptVersion,
        String idempotencyKey,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
