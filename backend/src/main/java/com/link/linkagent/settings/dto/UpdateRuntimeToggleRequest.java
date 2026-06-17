package com.link.linkagent.settings.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 运行期开关更新请求。
 */
public record UpdateRuntimeToggleRequest(
        @NotNull(message = "开关值不能为空")
        Boolean enabled
) {
}
