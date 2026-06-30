package com.link.linkagent.creator.suggestion.model;

import java.util.List;

/**
 * 发布前优化建议审查报告。
 * <p>
 * 第一版只做确定性规则审查，不再次调用 LLM，是为了用稳定、低成本的方式先拦住
 * “无证据建议、夸大承诺、缺少关键建议项”等高频质量问题。
 */
public record PrePublishAuditReport(
        String status,
        Integer score,
        String summary,
        List<PrePublishAuditIssue> issues,
        String auditedAt
) {
}
