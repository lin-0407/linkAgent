package com.link.linkagent.creator.workflow.model;

/**
 * 发布前优化阶段的 AI 文稿草稿响应。
 * 返回草稿正文，是为了前端刷新任务材料前也能立刻展示本次生成结果。
 */
public record PrePublishDraftResponse(
        String taskId,
        String sessionId,
        String materialType,
        String content,
        CreatorWorkflowMessageResponse message
) {
}
