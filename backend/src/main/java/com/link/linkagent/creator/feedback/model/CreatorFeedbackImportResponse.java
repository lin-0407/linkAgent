package com.link.linkagent.creator.feedback.model;

import java.util.List;

public record CreatorFeedbackImportResponse(
        String taskId,
        int commentCount,
        int danmakuCount,
        boolean metricImported,
        List<String> warnings
) {
}
