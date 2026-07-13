package com.link.linkagent.creator.media.upload.model;

/**
 * P0-0 需要的成片上传状态枚举。
 * <p>
 * 这些状态映射 creator_draft_video.status 列，由上传流程驱动。
 * 媒体探测（ffprobe）和试映状态在后续 P0-1 / P0-2 切片继续扩展。
 * <p>
 * 状态流转：
 * <pre>
 *   (null) → UPLOADING → UPLOADED    (上传成功)
 *                       → UPLOAD_FAILED  (上传失败)
 *                       → UPLOAD_ABORTED (用户取消)
 * </pre>
 */
public enum DraftVideoStatus {
    /** 正在上传分片中 */
    UPLOADING,
    /** 所有分片已上传并校验通过 */
    UPLOADED,
    /** 上传失败（校验不通过或 OSS 异常） */
    UPLOAD_FAILED,
    /** 用户主动取消上传 */
    UPLOAD_ABORTED
}
