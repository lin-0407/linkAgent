package com.link.linkagent.creator.media.upload.model;

import java.time.Instant;

/**
 * 单个分片短时签名 API 响应。
 * <p>
 * uploadUrl 是含签名的完整 PUT URL，前端使用 fetch/XHR 直接上传分片数据。
 * 响应体中的 URL 是 Bearer 凭证级别，因此不含缓存头且在日志中脱敏。
 *
 * @param partNumber 分片序号
 * @param uploadUrl  短时预签名 PUT URL（含认证签名，有效期 15 分钟）
 * @param expiresAt  URL 过期时间（前端据此在过期前刷新签名）
 */
public record PresignedMediaUploadPartResponse(
        int partNumber,
        String uploadUrl,
        Instant expiresAt
) {
}
