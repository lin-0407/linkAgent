package com.link.linkagent.creator.media.upload.model;

/**
 * P0-0 需要的成片上传状态枚举。
 * <p>
 * 这些状态映射 creator_draft_video.status 列，由上传流程驱动。
 * 媒体探测（ffprobe）在 P0-1 扩展 READY_FOR_REVIEW / PROBE_FAILED，试映状态在 P0-2 继续扩展。
 * <p>
 * 状态流转：
 * <pre>
 *   (null) → UPLOADING → UPLOADED → PROBING → READY_FOR_REVIEW  (探测成功，可开始媒体预处理)
 *                                            → PROBE_FAILED      (探测失败，可重试或重新上传)
 *                       → UPLOAD_FAILED                (上传失败)
 *                       → UPLOAD_ABORTED               (用户取消)
 * </pre>
 */
public enum DraftVideoStatus {
    /** 正在上传分片中 */
    UPLOADING,
    /** 所有分片已上传并校验通过 */
    UPLOADED,
    /** 已被一个探测请求领取，防止并发请求覆盖同一份媒体结果 */
    PROBING,
    /** 媒体探测通过，后续可进入正式发布前试映 */
    READY_FOR_REVIEW,
    /** 媒体探测失败，通常是文件损坏、时长超限或 ffprobe 不可用 */
    PROBE_FAILED,
    /** 上传失败（校验不通过或 OSS 异常） */
    UPLOAD_FAILED,
    /** 用户主动取消上传 */
    UPLOAD_ABORTED
}
