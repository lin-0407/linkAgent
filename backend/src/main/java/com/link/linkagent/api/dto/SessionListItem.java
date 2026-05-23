package com.link.linkagent.api.dto;

public record SessionListItem(
        String sessionId,
        String preview,
        long messageCount
) {
}
