package com.link.linkagent.creator.task.model;

import com.link.linkagent.util.TextUtil;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * 更新创作任务请求。
 * 采用覆盖式更新，是为了让前端编辑窗口里的四类材料和数据库保持一一对应，避免旧材料被误带入后续 Agent 分析。
 */
public record CreatorTaskUpdateRequest(
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

    @AssertTrue(message = "标题草稿、简介草稿、文稿、字幕至少保留一项")
    public boolean isAnyMaterialProvided() {
        return TextUtil.hasText(titleDraft)
                || TextUtil.hasText(descriptionDraft)
                || TextUtil.hasText(manuscript)
                || TextUtil.hasText(subtitle);
    }
}
