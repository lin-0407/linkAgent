package com.link.linkagent.creator.bilibili.model;

import java.time.LocalDateTime;

/**
 * 视频分析报告表（creator_video_analysis_report）数据库行记录。
 * 统一承载第三阶段"视频分析与复盘"的全部 LLM 分析结果，
 * 替代原先分散在 creator_llm_feedback_report 和 creator_report 的用户可见报告。
 * P0-3 先建表，P0-4 开始写入和读取。
 */
public record VideoAnalysisReportRecord(
        Long id,
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
