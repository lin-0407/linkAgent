package com.link.linkagent.memory;

/**
 * 短期对话记忆中的单条消息。
 */
public record MemoryMessage(
        String role,
        String content
) {
}
