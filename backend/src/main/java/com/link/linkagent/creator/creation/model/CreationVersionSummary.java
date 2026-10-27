package com.link.linkagent.creator.creation.model;

import java.time.LocalDateTime;

/** 历史列表只返回展示事实，不暴露私有存储位置。 */
public record CreationVersionSummary(String versionId, int versionNo, String versionName,
        String status, Long durationMs, boolean mediaAvailable, boolean contextAvailable,
        boolean reviewReady, boolean selected, boolean published, LocalDateTime createTime) {
}
