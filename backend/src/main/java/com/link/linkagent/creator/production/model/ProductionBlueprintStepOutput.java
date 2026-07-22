package com.link.linkagent.creator.production.model;

import java.util.List;

/** 模型生成的单步蓝图，落库前由应用服务校验必填字段。 */
public record ProductionBlueprintStepOutput(
        String phase,
        String stepName,
        String objective,
        List<String> prerequisites,
        List<String> operations,
        List<String> toolNames,
        List<String> expectedOutputs,
        List<String> acceptanceCriteria,
        String difficulty,
        Boolean required
) {
}
