package com.link.linkagent.creator.task.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 任务级发布前设置更新请求。
 * 字段与发布方案生成参数保持一致，保证页面保存的内容就是实际生成使用的内容。
 */
public record PrePublishSettingsUpdateRequest(
        @NotBlank(message = "偏好使用方式不能为空")
        @Pattern(
                regexp = "USE_HISTORY|IGNORE_HISTORY|EXPERIMENT",
                message = "偏好使用方式只能是 USE_HISTORY、IGNORE_HISTORY 或 EXPERIMENT"
        )
        String preferenceMode,

        @Size(max = 500, message = "创作目标长度不能超过500个字符")
        String creatorPreference,

        @Size(max = 100, message = "标题风格长度不能超过100个字符")
        String titleStyle,

        @Size(max = 500, message = "额外要求长度不能超过500个字符")
        String extraRequirement,

        @Size(max = 2000, message = "其它发布前语境长度不能超过2000个字符")
        String customGuidance
) {
}
