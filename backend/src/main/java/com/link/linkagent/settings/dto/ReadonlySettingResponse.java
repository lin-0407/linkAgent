package com.link.linkagent.settings.dto;

/**
 * 只读配置展示项。
 * 这些配置影响启动期装配，只能提示当前值，不能在运行期直接修改。
 */
public record ReadonlySettingResponse(
        String key,
        String name,
        String value,
        String description
) {
}
