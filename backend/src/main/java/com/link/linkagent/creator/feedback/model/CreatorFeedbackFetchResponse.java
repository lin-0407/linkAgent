package com.link.linkagent.creator.feedback.model;

import java.util.List;

/**
 * BV 评论弹幕采集响应。
 * 返回输出路径和导入数量，是为了让前端既能提示文件落点，也能立即刷新仪表盘。
 */
public record CreatorFeedbackFetchResponse(
        String taskId,
        String bvid,
        String outputDirectory,
        List<String> outputFiles,
        int commentCount,
        int danmakuCount,
        boolean metricImported,
        List<String> warnings
) {
}
