package com.link.linkagent.creator.bilibili.model;

import java.time.LocalDateTime;

/**
 * B站账号绑定 API 响应。
 * 不含数据库自增 id 和逻辑删除标记，只暴露业务字段给前端。
 */
public record BilibiliAccountResponse(
        String accountId,
        String userId,
        String bilibiliUid,
        String nickname,
        String avatarUrl,
        String bindStatus,
        LocalDateTime lastSyncTime,
        String lastSyncError,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
