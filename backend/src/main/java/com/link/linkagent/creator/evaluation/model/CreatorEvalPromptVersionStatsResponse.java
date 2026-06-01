package com.link.linkagent.creator.evaluation.model;

import java.time.LocalDateTime;

/**
 * Prompt 版本评测统计。
 * 这里按评测用例聚合不同 Prompt 版本，是为了让作者能对比“哪一版提示词更稳”，而不是只保存零散结果。
 */
public record CreatorEvalPromptVersionStatsResponse(
        String caseId,
        String promptVersion,
        String latestPromptHash,
        int resultCount,
        int successCount,
        Double successRatePercent,
        int scoreSampleCount,
        Double averageScore,
        Double scoreStandardDeviation,
        Double averageReadabilityScore,
        Double averageRelevanceScore,
        Double averageCompletenessScore,
        Double averageAccuracyScore,
        Double averageStabilityScore,
        Double averageCostScore,
        Double averageExplainabilityScore,
        Long totalPromptTokens,
        Long totalCompletionTokens,
        Long totalTokens,
        Double averagePromptTokens,
        Double averageCompletionTokens,
        Double averageTotalTokens,
        Double averageElapsedMs,
        Double fullScoreCoverageRatePercent,
        LocalDateTime latestUpdateTime
) {
}
