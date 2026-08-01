package com.link.linkagent.creator.task.model;

import java.time.LocalDateTime;

public record PrePublishSettingsResponse(
        String taskId,
        String preferenceMode,
        String creatorPreference,
        String titleStyle,
        String extraRequirement,
        String customGuidance,
        LocalDateTime updateTime
) {
}
