package com.link.linkagent.creator.media.processing.model;

import java.time.Instant;

/**
 * 私有派生素材的浏览器短时读取地址。
 */
public record MediaProcessingAssetReadUrlResponse(
        String assetId,
        String readUrl,
        Instant expiresAt
) {
}
