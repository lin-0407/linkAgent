package com.link.linkagent.settings.dto;

/**
 * 可动态修改的运行期开关展示项。
 */
public record RuntimeToggleResponse(
        String key,
        String name,
        boolean enabled,
        String description
) {
}
