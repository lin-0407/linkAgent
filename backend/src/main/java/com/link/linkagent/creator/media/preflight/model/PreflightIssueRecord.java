package com.link.linkagent.creator.media.preflight.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 发布前体检问题持久化记录。 */
public record PreflightIssueRecord(
        Long id,
        String issueId,
        String reviewId,
        String versionId,
        String issueType,
        String dimension,
        String title,
        String description,
        Long startMs,
        Long endMs,
        String severity,
        BigDecimal confidence,
        String evidenceRefs,
        String suggestedAction,
        Boolean needsHumanReview,
        String sourceTypes,
        String affectedPersonas,
        String userDisposition,
        String ignoreReason,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
