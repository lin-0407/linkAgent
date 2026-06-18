package com.link.linkagent.knowledge.model;

import java.util.List;

/**
 * 单个视频的分析上下文响应。
 * <p>
 * 前端点击卡片后用这个接口自动加载主题中块和评论弹幕，后续 AI 交互台就能围绕该视频继续问答。
 */
public record ReferenceVideoAnalysisContextResponse(
        ReferenceVideoResponse video,
        List<ReferenceVideoMatchedTopic> topics,
        List<ReferenceVideoEvidenceItem> evidenceItems
) {
}
