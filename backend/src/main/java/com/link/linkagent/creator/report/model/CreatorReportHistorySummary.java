package com.link.linkagent.creator.report.model;

import java.time.LocalDateTime;

/** 历史列表只返回摘要，避免一次加载所有报告全文。 */
public record CreatorReportHistorySummary(
        String historyId, String overallConclusion, Boolean hasCompetitor, LocalDateTime createTime
) {
}
