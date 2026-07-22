package com.link.linkagent.creator.production.model;

import java.time.LocalDateTime;
import java.util.List;

/** 制作蓝图对外响应，不直接暴露模型原始输出和内部快照。 */
public record ProductionPlanResponse(
        String planId,
        String taskId,
        Integer planVersion,
        String videoCategory,
        String productionMethod,
        String targetAudience,
        String corePromise,
        Long targetDurationMs,
        List<String> availableAssets,
        String constraints,
        String status,
        String planTitle,
        String positioningSummary,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
