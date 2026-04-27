package com.link.linkagent.core;

/**
 * Tool 执行后拼接到 conversation 的观察结果。
 */
public record Observation(
        String toolName,
        String result
) {
}
