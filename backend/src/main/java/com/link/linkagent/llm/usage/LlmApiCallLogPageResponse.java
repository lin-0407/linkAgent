package com.link.linkagent.llm.usage;

import java.util.List;

/** 全局模型调用日志分页响应，汇总与明细使用完全相同的筛选条件。 */
public record LlmApiCallLogPageResponse(
        int page,
        int pageSize,
        long total,
        LlmApiCallLogSummaryResponse summary,
        List<LlmApiCallRecord> items
) {
}
