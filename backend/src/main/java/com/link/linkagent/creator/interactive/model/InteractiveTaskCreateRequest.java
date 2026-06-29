package com.link.linkagent.creator.interactive.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 交互式创作任务创建请求。
 * 用户只需要输入自然语言想法，后端负责把它沉淀为标准创作任务和创意卡片。
 */
public record InteractiveTaskCreateRequest(
        @Size(max = 64, message = "用户ID长度不能超过64个字符")
        String userId,

        @NotBlank(message = "创作想法不能为空")
        @Size(min = 10, max = 3000, message = "创作想法长度必须在10到3000个字符之间")
        String idea,

        @Size(max = 64, message = "视频类型长度不能超过64个字符")
        String videoType
) {
}
