package com.link.linkagent.creator.workflow.model;

import jakarta.validation.constraints.Size;

/**
 * 发布前优化阶段的 AI 文稿草稿请求。
 * 额外要求允许为空，是为了让用户在没有文稿时可以一键让 AI 先补一版可编辑草稿。
 */
public record PrePublishDraftRequest(
        @Size(max = 1000, message = "草稿补充要求长度不能超过1000个字符")
        String extraRequirement
) {
}
