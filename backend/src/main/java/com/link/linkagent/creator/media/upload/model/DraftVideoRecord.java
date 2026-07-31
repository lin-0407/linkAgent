package com.link.linkagent.creator.media.upload.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * creator_draft_video 数据库记录。
 * <p>
 * 使用 Java record 作为 MyBatis 结果映射的载体：字段名与数据库列的 property 一致。
 * P0-1 开始映射媒体探测字段，供前端展示和后续试映链路判断。
 *
 * @param id               自增主键（MyBatis 插入后可回填，但 P0 未使用）
 * @param versionId        成片版本唯一标识（UUID）
 * @param taskId           关联创作任务 ID
 * @param ownerId          可信媒体归属（P0 固定为 default）
 * @param versionNo        版本号（P0 固定为 1）
 * @param versionName      用户填写的版本名称
 * @param originalFileName 用户原文件名（仅展示用，不参与对象键生成）
 * @param bucketName       原片所在 OSS Bucket 名称
 * @param objectKey        后端生成的原片私有对象键
 * @param contentType      对象存储记录的媒体类型
 * @param fileSize         文件字节数
 * @param durationMs       ffprobe 探测的视频时长毫秒
 * @param width            视频宽度
 * @param height           视频高度
 * @param frameRate        平均帧率
 * @param videoCodec       视频编码
 * @param audioCodec       音频编码，无音轨时为空
 * @param hasAudio         是否存在音轨
 * @param probeAttemptId   当前媒体探测领取标识
 * @param status           成片状态
 * @param mediaDeletedAt   原片和派生媒体删除时间；为空表示媒体仍可读取
 * @param createTime       创建时间
 * @param updateTime       最后更新时间
 */
public record DraftVideoRecord(
        Long id,
        String versionId,
        String taskId,
        String ownerId,
        Integer versionNo,
        String versionName,
        String originalFileName,
        String bucketName,
        String objectKey,
        String contentType,
        Long fileSize,
        Long durationMs,
        Integer width,
        Integer height,
        BigDecimal frameRate,
        String videoCodec,
        String audioCodec,
        Boolean hasAudio,
        String probeAttemptId,
        String status,
        LocalDateTime mediaDeletedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
