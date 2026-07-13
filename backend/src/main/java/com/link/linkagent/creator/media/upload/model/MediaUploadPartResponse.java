package com.link.linkagent.creator.media.upload.model;

import java.time.LocalDateTime;

/**
 * 已登记分片 API 响应。
 * <p>
 * 供前端查询已完成分片列表，用于续传时跳过已完成分片和展示上传进度。
 *
 * @param partNumber  分片序号
 * @param etag        分片 ETag（不透明值，仅用于展示）
 * @param partSize    分片字节数
 * @param completedAt 分片登记时间
 */
public record MediaUploadPartResponse(
        int partNumber,
        String etag,
        long partSize,
        LocalDateTime completedAt
) {
}
