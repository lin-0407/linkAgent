package com.link.linkagent.creator.workflow.model;

/**
 * 审查 Agent 发现的一处偏离。
 * 只允许引用用户原话和说明偏离原因，不提供改写建议，避免审查意见稀释用户本意。
 */
public record CreatorIntentReviewIssue(
        String userQuote,
        String reason
) {
}
