package com.link.linkagent.creator.competitor.model;

import java.time.LocalDateTime;

public record CreatorCompetitorReportResponse(
        Long id,
        String reportId,
        String taskId,
        String competitorSummary,
        String competitorAdvantages,
        String ownAdvantages,
        String ownDisadvantages,
        String gapAnalysis,
        String improvementSuggestions,
        String differentiationStrategy,
        String rawOutput,
        String parseStatus,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
