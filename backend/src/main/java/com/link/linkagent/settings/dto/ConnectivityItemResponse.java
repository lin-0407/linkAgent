package com.link.linkagent.settings.dto;

/**
 * 单个基础设施检测结果。
 */
public record ConnectivityItemResponse(
        String key,
        String name,
        String status,
        String message
) {
}
