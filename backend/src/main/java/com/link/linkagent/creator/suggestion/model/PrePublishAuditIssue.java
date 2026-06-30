package com.link.linkagent.creator.suggestion.model;

import java.util.List;

/**
 * 发布前优化建议审查问题。
 * <p>
 * 审查问题用于把“AI 建议哪里不可靠”结构化保存下来，后续前端可以直接提示作者复核，
 * 也方便评测集统计常见失败类型。
 */
public record PrePublishAuditIssue(
        String severity,
        String code,
        String target,
        String message,
        List<String> evidenceIds
) {
}
