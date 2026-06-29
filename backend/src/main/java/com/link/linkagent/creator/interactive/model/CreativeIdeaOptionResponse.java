package com.link.linkagent.creator.interactive.model;

import java.time.LocalDateTime;

public record CreativeIdeaOptionResponse(
        Long id,
        String optionId,
        String sessionId,
        String taskId,
        String optionName,
        String targetAudience,
        String titleOutline,
        String contentOutline,
        String descriptionOutline,
        String sellingPoints,
        String riskPoints,
        String recommendReason,
        Boolean selected,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
