package com.link.linkagent.creator.bilibili.model;

import java.time.LocalDateTime;

/**
 * B站视频缓存 API 响应。
 * 相比 record 多了 hasTaskBinding / taskId / taskName 三个关联展示字段，
 * 让前端可以在一张视频卡片上同时看到视频信息和关联的任务名称。
 * 不含数据库自增 id 和逻辑删除标记。
 */
public record BilibiliVideoResponse(
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
        boolean hasTaskBinding,
        String taskId,
        String taskName
) {
}
