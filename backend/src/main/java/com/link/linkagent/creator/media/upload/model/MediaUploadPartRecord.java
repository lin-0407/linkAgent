package com.link.linkagent.creator.media.upload.model;

import java.time.LocalDateTime;

/**
 * creator_media_upload_part 数据库记录。
 * <p>
 * 映射已登记分片的数据表行，作为分片续传和完成校验的事实来源。
 *
 * @param id              自增主键
 * @param uploadSessionId 上传会话 ID（UUID）
 * @param partNumber      分片序号（1-based）
 * @param etag            分片 ETag（来自 OSS PUT 响应头）
 * @param partSize        分片实际字节数（浏览器确认值）
 * @param completedAt     分片登记时间
 * @param createTime      创建时间
 * @param updateTime      最后更新时间
 */
public record MediaUploadPartRecord(
        Long id,
        String uploadSessionId,
        Integer partNumber,
        String etag,
        Long partSize,
        LocalDateTime completedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
