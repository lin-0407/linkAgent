package com.link.linkagent.creator.bilibili.model;

import java.time.LocalDateTime;

/**
 * 任务视频绑定表（creator_task_video_binding）数据库行记录。
 * 每个创作任务最多绑定一个 BV 号，绑定后视频分析页才能展示该视频卡片。
 * 绑定不要求 B 站账号已同步——用户可以先填 BV，后续再绑定 UID 校验归属。
 */
public record TaskVideoBindingRecord(
        Long id,
        String bindingId,
        String taskId,
        String userId,
        String bilibiliUid,
        String bvid,
        String bindingStatus,
        String verifyMessage,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
