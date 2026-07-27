package com.link.linkagent.creator.workflow.model;

import java.util.List;

/**
 * 审查 Agent 的结构化结果。
 */
public record CreatorIntentReviewResult(
        boolean deviated,
        List<CreatorIntentReviewIssue> issues
) {
}
