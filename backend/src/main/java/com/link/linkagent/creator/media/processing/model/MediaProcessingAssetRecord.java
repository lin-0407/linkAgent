package com.link.linkagent.creator.media.processing.model;

import java.time.LocalDateTime;

/**
 * creator_media_processing_asset 数据库记录。
 */
public record MediaProcessingAssetRecord(
        Long id,
        String assetId,
        String jobId,
        String versionId,
        String assetType,
        String bucketName,
        String objectKey,
        String contentType,
        Long fileSize,
        Integer sequenceNo,
        Long timestampMs,
        Integer width,
        Integer height,
        Long durationMs,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
