package com.link.linkagent.creator.media.upload.model;

import java.time.LocalDateTime;

/**
 * 上传会话快照 API 响应。
 * <p>
 * 页面刷新后前端依靠该响应和分片列表恢复续传状态。
 * completedPartCount 是动态计算值（从分片表 COUNT 得到），不在数据库存储。
 *
 * @param uploadSessionId   上传会话 ID
 * @param versionId         成片版本 ID
 * @param taskId            创作任务 ID
 * @param status            当前会话状态
 * @param expectedSize      文件预期总字节数
 * @param fileFingerprint   文件指纹（续传对账用）
 * @param idempotencyKey    创建会话的幂等键，浏览器丢失本地指针后用于恢复同一会话
 * @param partSize          单分片大小
 * @param totalParts        总分片数
 * @param completedPartCount 已完成分片数（前端据此计算进度百分比）
 * @param expiresAt          会话过期时间
 * @param completedAt        完成时间（未完成时为 null）
 * @param failureMessage     失败原因（未失败时为 null）
 */
public record MediaUploadResponse(
        String uploadSessionId,
        String versionId,
        String taskId,
        String status,
        long expectedSize,
        String fileFingerprint,
        String idempotencyKey,
        int partSize,
        int totalParts,
        int completedPartCount,
        LocalDateTime expiresAt,
        LocalDateTime completedAt,
        String failureMessage
) {
}
