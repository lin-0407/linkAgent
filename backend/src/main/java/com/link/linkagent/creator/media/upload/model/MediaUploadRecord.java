package com.link.linkagent.creator.media.upload.model;

import java.time.LocalDateTime;

/**
 * creator_media_upload 数据库记录。
 * <p>
 * 完整映射上传会话表的所有列，是上传流程中的核心数据载体。
 * 包含 OSS Upload ID、对象键、分片参数、指纹和状态信息。
 *
 * @param id               自增主键
 * @param uploadSessionId  业务上传会话唯一标识（UUID）
 * @param versionId        关联的成片版本 ID
 * @param taskId           关联的创作任务 ID
 * @param ownerId          可信媒体归属
 * @param storageUploadId  OSS Multipart Upload ID
 * @param objectKey        后端生成的对象键
 * @param contentType      声明的媒体类型
 * @param expectedSize     客户端声明的文件总字节数
 * @param fileFingerprint  文件名+大小+修改时间的 SHA-256 摘要
 * @param partSize         单分片目标字节数
 * @param totalParts       预期分片总数
 * @param status           上传会话状态
 * @param idempotencyKey   创建上传的幂等键
 * @param failureMessage   最近失败原因摘要
 * @param expiresAt        上传会话过期时间
 * @param completedAt      完整对象确认完成时间
 * @param createTime       创建时间
 * @param updateTime       最后更新时间
 */
public record MediaUploadRecord(
        Long id,
        String uploadSessionId,
        String versionId,
        String taskId,
        String ownerId,
        String storageUploadId,
        String objectKey,
        String contentType,
        Long expectedSize,
        String fileFingerprint,
        Integer partSize,
        Integer totalParts,
        String status,
        String idempotencyKey,
        String failureMessage,
        LocalDateTime expiresAt,
        LocalDateTime completedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
