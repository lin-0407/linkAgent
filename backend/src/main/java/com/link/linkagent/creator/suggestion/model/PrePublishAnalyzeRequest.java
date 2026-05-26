package com.link.linkagent.creator.suggestion.model;

import jakarta.validation.constraints.Size;

/**
 * 发布前优化分析请求。
 * 核心材料从任务中读取，这里只接收用户本次想补充的偏好，避免重复提交长文稿。
 */
public record PrePublishAnalyzeRequest(
        @Size(max = 500, message = "创作者偏好长度不能超过500个字符")
        String creatorPreference,

        @Size(max = 100, message = "标题风格长度不能超过100个字符")
        String titleStyle,

        @Size(max = 500, message = "额外要求长度不能超过500个字符")
        String extraRequirement
) {
}
