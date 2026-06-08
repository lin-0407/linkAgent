package com.link.linkagent.knowledge.model;

import java.util.List;

/**
 * 按 videoId 分组的召回证据（阶段 5.2c-2，响应方案 a）：这张父表案例卡片是被哪几条子条目召回的。
 * <p>
 * 放在检索响应<b>顶层</b>（与 {@code items} 平级、按 videoId 关联）而非内嵌进卡片，是为了让
 * {@code items} 继续复用 {@code ReferenceVideoResponse[]}——5.2a/b 的卡片渲染与列表复用完全不动（方案 a 的核心收益）。
 * 只对最终返回的卡片中「有子命中」者才出现在本列表，无子命中的卡片不占位。
 */
public record ReferenceVideoEvidence(
        String videoId,
        List<ReferenceVideoEvidenceItem> items
) {
}
