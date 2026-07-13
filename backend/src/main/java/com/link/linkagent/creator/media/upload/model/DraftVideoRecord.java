package com.link.linkagent.creator.media.upload.model;

import java.time.LocalDateTime;

/**
 * creator_draft_video 数据库记录。
 * <p>
 * 使用 Java record 作为 MyBatis 结果映射的载体：字段名与数据库列的 property 一致。
 * P0 只映射核心字段，媒体探测字段（duration_ms、width、height 等）在 P0-1 扩展。
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
 * @param status           成片状态
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
        String status,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
