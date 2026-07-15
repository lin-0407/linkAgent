package com.link.linkagent.creator.bilibili.model;

import java.util.List;

/**
 * B站公开视频同步接口响应。
 * 保留原占位接口已有计数字段，并补充成功状态、分页提示和可展示警告。
 */
public record BilibiliVideoSyncResponse(
        String bilibiliUid,
        String syncStatus,
        int syncedCount,
        int linkedCount,
        int anomalyCount,
        String lastError,
        List<String> warnings,
        boolean hasMore,
        String message
) {

    public BilibiliVideoSyncResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
