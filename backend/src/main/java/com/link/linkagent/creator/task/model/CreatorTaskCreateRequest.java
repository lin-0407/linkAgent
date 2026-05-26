package com.link.linkagent.creator.task.model;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * 创建创作任务请求。
 * 第一版只接收用户主动输入的文本材料，避免把项目重心带到平台爬取或账号授权。
 */
public record CreatorTaskCreateRequest(
        @Size(max = 64, message = "用户ID长度不能超过64个字符")
        String userId,

        @Size(max = 128, message = "任务名称长度不能超过128个字符")
        String taskName,

        @Size(max = 200, message = "标题草稿长度不能超过200个字符")
        String titleDraft,

        @Size(max = 2000, message = "简介草稿长度不能超过2000个字符")
        String descriptionDraft,

        @Size(max = 20000, message = "文稿长度不能超过20000个字符")
        String manuscript,

        @Size(max = 20000, message = "字幕长度不能超过20000个字符")
        String subtitle
) {

    @AssertTrue(message = "标题草稿、简介草稿、文稿、字幕至少填写一项")
    public boolean isAnyMaterialProvided() {
        return hasText(titleDraft)
                || hasText(descriptionDraft)
                || hasText(manuscript)
                || hasText(subtitle);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
