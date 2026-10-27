package com.link.linkagent.creator.creation.model;

/** 将系统步骤精确绑定到制作运行，状态仍以原蓝图步骤表为准。 */
public record AutomaticStepEvidence(String runId, String taskId, String ownerId, String productionPlanId,
        String stepCode, String stepId, String evidenceJson, Long leaseGeneration, String attemptId, String stepStatus) { }
