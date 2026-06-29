package com.link.linkagent.creator.bilibili.model;

import java.time.LocalDateTime;

/**
 * B站账号绑定表（creator_bilibili_account）数据库行记录。
 * 与 creator_task 平行存储，用于隔离账号绑定和任务业务逻辑。
 * 第一版只记录用户手动填写的 B 站 UID，不做 OAuth 授权。
 */
public record BilibiliAccountRecord(
        Long id,
        String accountId,
        String userId,
        String bilibiliUid,
        String nickname,
        String bindStatus,
        LocalDateTime lastSyncTime,
        String lastSyncError,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
