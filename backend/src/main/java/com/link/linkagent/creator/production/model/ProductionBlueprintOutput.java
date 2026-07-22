package com.link.linkagent.creator.production.model;

import java.util.List;

/** 两类制作蓝图共用的结构化模型输出。 */
public record ProductionBlueprintOutput(
        String planTitle,
        String positioningSummary,
        List<ProductionBlueprintStepOutput> steps
) {
}
