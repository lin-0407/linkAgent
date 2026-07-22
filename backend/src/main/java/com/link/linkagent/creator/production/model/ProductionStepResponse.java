package com.link.linkagent.creator.production.model;

import java.util.List;

/** 制作步骤对外响应。 */
public record ProductionStepResponse(
        String stepId,
        Integer sequenceNo,
        String phase,
        String stepName,
        String objective,
        List<String> prerequisites,
        List<String> operations,
        List<ToolResolutionResponse> toolRefs,
        List<String> expectedOutputs,
        List<String> acceptanceCriteria,
        String difficulty,
        Boolean required,
        String status,
        Long rowVersion,
        String skipReason
) {
}
