package com.link.linkagent.creator.report.model;

import java.util.List;

public record CreatorReportHistoryPage(
        List<CreatorReportHistorySummary> items, long total, int page, int size
) {
}
