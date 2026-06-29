package com.link.linkagent.creator.bilibili.model;

import java.time.LocalDateTime;

/**
 * 视频分析报告 API 响应。
 * P0-4 起前端用此响应渲染完整视频分析报告页面。
 * 不含数据库自增 id 和逻辑删除标记。
 */
public record VideoAnalysisReportResponse(
        String analysisId,
        String taskId,
        String bvid,
        String workflowSessionId,
        String analysisStatus,
        String oneSentenceSummary,
        String publishPlanReview,
        String audienceFocus,
        String misunderstandingPoints,
        String controversyPoints,
        String nextActionPlan,
        String evidenceSummary,
        String rawOutput,
        String parseStatus,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
