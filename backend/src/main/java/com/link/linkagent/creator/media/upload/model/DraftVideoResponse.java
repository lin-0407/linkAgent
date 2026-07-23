package com.link.linkagent.creator.media.upload.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 上传完成后的成片事实 API 响应。
 * <p>
 * 包含成片的完整信息，供前端展示和后续流程（如试映、发布）使用。
 * 不包含对象键（objectKey）和桶名（bucketName），前端不需要知道对象存储的内部路径。
 *
 * @param versionId        成片版本唯一标识（UUID）
 * @param taskId           关联的创作任务 ID
 * @param versionNo        版本号（P0 固定为 1）
 * @param versionName      用户填写的版本名称
 * @param originalFileName 用户原文件名（仅展示用）
 * @param contentType      媒体类型（以对象存储实际记录为准）
 * @param fileSize         文件字节数（以对象存储实际记录为准）
 * @param durationMs       ffprobe 探测的视频时长毫秒，未探测时为空
 * @param width            视频宽度，未探测时为空
 * @param height           视频高度，未探测时为空
 * @param frameRate        平均帧率，未探测时为空
 * @param videoCodec       视频编码，未探测时为空
 * @param audioCodec       音频编码，无音轨时为空
 * @param hasAudio         是否存在音轨，未探测时为空
 * @param status           成片状态（READY_FOR_REVIEW 表示探测通过，仍需完成媒体预处理）
 * @param createTime       创建时间
 * @param updateTime       最后更新时间
 */
public record DraftVideoResponse(
        String versionId,
        String taskId,
        int versionNo,
        String versionName,
        String originalFileName,
        String contentType,
        long fileSize,
        Long durationMs,
        Integer width,
        Integer height,
        BigDecimal frameRate,
        String videoCodec,
        String audioCodec,
        Boolean hasAudio,
        String status,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
