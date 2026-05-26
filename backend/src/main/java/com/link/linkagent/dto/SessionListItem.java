package com.link.linkagent.dto;

public record SessionListItem(
        String sessionId,
        String preview,
        long messageCount
) {
}
