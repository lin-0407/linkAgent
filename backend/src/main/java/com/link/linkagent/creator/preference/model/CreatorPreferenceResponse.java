package com.link.linkagent.creator.preference.model;

import java.time.LocalDateTime;

public record CreatorPreferenceResponse(
        Long id,
        String preferenceId,
        String userId,
        String sourceTaskId,
        String sourceReportId,
        String preferenceContent,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
