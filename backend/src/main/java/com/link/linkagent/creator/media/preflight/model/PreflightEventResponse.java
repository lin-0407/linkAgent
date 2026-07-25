package com.link.linkagent.creator.media.preflight.model;

import java.time.OffsetDateTime;
import java.util.Map;

/** 试映 SSE 增量提示；最终事实始终以 GET 快照为准。 */
public record PreflightEventResponse(
        String eventId,
        String taskId,
        String reviewId,
        long sequenceNo,
        String eventType,
        OffsetDateTime occurredAt,
        Map<String, Object> payload
) {
}
