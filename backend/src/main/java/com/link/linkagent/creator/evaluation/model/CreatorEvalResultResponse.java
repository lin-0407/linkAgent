package com.link.linkagent.creator.evaluation.model;

import java.time.LocalDateTime;

/**
 * 评测结果返回对象。
 * 评测结果里同时保留原始输出、失败原因和人工评分，后面做失败回放时才能看清楚到底卡在哪里。
 */
public record CreatorEvalResultResponse(
        Long id,
        String resultId,
        String caseId,
        String taskId,
        String workflowSessionId,
        String targetStage,
        String modelName,
        String promptVersion,
        String promptHash,
        String promptSnapshot,
        String outputSummary,
        String rawOutput,
        String runStatus,
        String parseStatus,
        Long elapsedMs,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        String failureReason,
        Integer readabilityScore,
        Integer relevanceScore,
        Integer completenessScore,
        Integer accuracyScore,
        Integer stabilityScore,
        Integer costScore,
        Integer explainabilityScore,
        String reviewerNote,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
