package com.link.linkagent.creator.bilibili.model;

import java.time.LocalDateTime;

/**
 * B站视频缓存表（creator_bilibili_video）数据库行记录。
 * 缓存从 B 站公开接口同步的视频基础信息和指标，避免每次页面访问都重复请求 B 站 API。
 * 与 creator_task_video_binding 配合使用：binding 关联任务和 BV，video 缓存 BV 的展示数据。
 */
public record BilibiliVideoRecord(
        Long id,
        String videoId,
        String bilibiliUid,
        String bvid,
        Long aid,
        String title,
        String coverUrl,
        LocalDateTime publishTime,
        Long viewCount,
        Long likeCount,
        Long coinCount,
        Long favoriteCount,
        Long shareCount,
        String syncStatus,
        LocalDateTime lastSyncTime,
        String rawSnapshot,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
