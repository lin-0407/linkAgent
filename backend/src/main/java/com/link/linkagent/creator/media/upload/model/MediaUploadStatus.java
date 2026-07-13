package com.link.linkagent.creator.media.upload.model;

/**
 * 媒体分片上传会话状态枚举。
 * <p>
 * 映射 creator_media_upload.status 列，完整覆盖分片上传生命周期：
 * <pre>
 *   CREATED → UPLOADING → VERIFYING → COMPLETED   (正常完成)
 *                     ↘ ABORTED                   (用户取消)
 *                     ↘ EXPIRED                   (超时过期)
 *                     ↘ FAILED → SUPERSEDED       (失败后被新尝试替代)
 * </pre>
 * <p>
 * VERIFYING 是关键中间态：CompleteMultipartUpload 请求已发出但未确认，
 * 用于处理 OSS 成功但网络丢包的容错场景。
 */
public enum MediaUploadStatus {
    /** 上传会话已创建，等待首次分片签名 */
    CREATED,
    /** 分片正在上传中 */
    UPLOADING,
    /** Complete 请求已发出，正在确认结果 */
    VERIFYING,
    /** 上传完成并校验通过（终态） */
    COMPLETED,
    /** 用户主动取消（终态） */
    ABORTED,
    /** 超时自动过期（终态） */
    EXPIRED,
    /** 校验失败（终态） */
    FAILED,
    /** 被新上传尝试替代（终态） */
    SUPERSEDED
}
