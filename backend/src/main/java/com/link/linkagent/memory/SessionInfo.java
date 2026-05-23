package com.link.linkagent.memory;

/**
 * 会话列表中展示的元数据。
 */
public record SessionInfo(
        String sessionId,
        String preview,
        long messageCount
) {
}
