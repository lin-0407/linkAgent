package com.link.linkagent.creator.media.preflight.model;

import java.time.LocalDateTime;

/** 作者从体检问题生成的成片修改任务。 */
public record EditTaskRecord(
        Long id,
        String editTaskId,
        String reviewId,
        String issueId,
        String taskId,
        String versionId,
        String title,
        String action,
        Long startMs,
        Long endMs,
        String priority,
        String targetOutcome,
        String status,
        String userNote,
        LocalDateTime completedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
