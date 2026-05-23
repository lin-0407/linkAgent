package com.link.linkagent.memory;

/**
 * One message stored in short-term conversation memory.
 */
public record MemoryMessage(
        String role,
        String content
) {
}
