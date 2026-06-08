package com.link.linkagent.knowledge.model;

/**
 * 单条召回证据（阶段 5.2c-2）：small-to-big 检索中精确命中的优质评论 / 弹幕原文。
 * <p>
 * 由子向量库召回 itemId 后<b>回查子表事实源</b>（is_deleted=0）组装而成——不直接用子向量文档里的文本，
 * 因为后者可能被截断且绕过软删。展示用途：告诉创作者「这个案例是被哪条观众原话召回的」。
 */
public record ReferenceVideoEvidenceItem(
        String itemId,
        String content,
        String sentiment,
        String sourceType
) {
}
