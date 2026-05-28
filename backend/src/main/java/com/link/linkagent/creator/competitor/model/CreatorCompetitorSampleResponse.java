package com.link.linkagent.creator.competitor.model;

import java.time.LocalDateTime;

public record CreatorCompetitorSampleResponse(
        Long id,
        String competitorBvId,
        String competitorVideoName,
        String taskId,
        String category,
        String competitorSamples,
        String compareDimension,
        String extraContext,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
