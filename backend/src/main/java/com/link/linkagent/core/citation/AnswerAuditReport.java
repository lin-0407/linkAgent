package com.link.linkagent.core.citation;

import com.link.linkagent.util.TextUtil;

import java.util.List;

/**
 * Synthesizer 输出后的轻量审查报告。
 */
public record AnswerAuditReport(
        boolean passed,
        String overallComment,
        List<AnswerAuditIssue> issues,
        List<String> rewriteInstructions
) {

    public AnswerAuditReport {
        overallComment = TextUtil.trimToDefault(overallComment, "");
        issues = issues == null ? List.of() : issues.stream()
                .filter(issue -> issue != null)
                .toList();
        rewriteInstructions = rewriteInstructions == null ? List.of() : rewriteInstructions.stream()
                .filter(TextUtil::hasText)
                .map(String::trim)
                .toList();
    }
}
