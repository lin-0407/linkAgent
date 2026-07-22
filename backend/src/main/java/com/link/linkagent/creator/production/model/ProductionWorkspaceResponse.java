package com.link.linkagent.creator.production.model;

import java.util.List;

/** 制作方案页一次恢复所需的聚合快照。 */
public record ProductionWorkspaceResponse(
        ProductionPlanResponse plan,
        List<ProductionStepResponse> steps,
        List<ToolResolutionResponse> toolResolution,
        boolean readyForMedia
) {
}
