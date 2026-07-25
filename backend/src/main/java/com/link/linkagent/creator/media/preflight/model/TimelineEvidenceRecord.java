package com.link.linkagent.creator.media.preflight.model;

import java.math.BigDecimal;

/** 统一毫秒时间轴证据记录。 */
public record TimelineEvidenceRecord(
        Long id,
        String evidenceId,
        String reviewId,
        String versionId,
        String sourceType,
        Long startMs,
        Long endMs,
        String content,
        BigDecimal confidence,
        String assetId,
        Boolean assetAvailable,
        String sourceStepId,
        String metadataJson
) {
}
