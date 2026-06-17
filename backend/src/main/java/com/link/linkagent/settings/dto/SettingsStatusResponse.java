package com.link.linkagent.settings.dto;

import java.util.List;

/**
 * 设置抽屉状态响应。
 */
public record SettingsStatusResponse(
        List<RuntimeToggleResponse> dynamicToggles,
        List<ReadonlySettingResponse> readonlySettings
) {
}
