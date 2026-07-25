package com.link.linkagent.creator.media.preflight.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 三类观众共用证据生成的单个角色试映记录。 */
public record AudienceScreeningRecord(
        Long id,
        String screeningId,
        String reviewId,
        String personaType,
        String personaSnapshot,
        String overallReaction,
        String interestPoints,
        String confusionPoints,
        String dropRisks,
        String evidenceRefs,
        BigDecimal confidence,
        String promptVersion,
        String rawOutput,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
