package com.link.linkagent.creator.bilibili.model;

import java.time.LocalDateTime;

/**
 * 任务视频绑定 API 响应。
 * 前端用此响应展示绑定状态、BV 号和校验说明，不含数据库自增 id 和逻辑删除标记。
 */
public record TaskVideoBindingResponse(
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
