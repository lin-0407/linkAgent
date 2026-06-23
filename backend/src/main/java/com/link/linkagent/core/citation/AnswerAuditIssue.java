package com.link.linkagent.core.citation;

import com.link.linkagent.util.TextUtil;

import java.util.List;

/**
 * 答案审查发现的问题。
 */
public record AnswerAuditIssue(
        String issueType,
        String description,
        List<String> relatedEvidenceIds
) {

    public AnswerAuditIssue {
        issueType = TextUtil.trimToDefault(issueType, "UNKNOWN");
        description = TextUtil.trimToDefault(description, "未说明问题");
        relatedEvidenceIds = relatedEvidenceIds == null ? List.of() : List.copyOf(relatedEvidenceIds);
    }
}
