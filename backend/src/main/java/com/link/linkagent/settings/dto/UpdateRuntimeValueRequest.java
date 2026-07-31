package com.link.linkagent.settings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 运行期枚举值更新请求。
 */
public record UpdateRuntimeValueRequest(
        @NotBlank(message = "设置值不能为空")
        @Size(max = 32, message = "设置值长度不能超过32个字符")
        String value
) {
}
