package com.link.linkagent.creator.suggestion.model;

/**
 * 发布前优化证据引用。
 * <p>
 * 证据引用用于约束 Agent：标题、简介和修改计划不能只凭模型感觉生成，必须尽量回到任务材料、
 * 创作者偏好、类型语境或同类案例。保存这份证据包，是为了后续能回放“这条建议为什么会出现”。
 */
public record PrePublishEvidenceRef(
        String evidenceId,
        String type,
        String sourceName,
        String sourceRef,
        String quote,
        String summary,
        Double confidence
) {
}
