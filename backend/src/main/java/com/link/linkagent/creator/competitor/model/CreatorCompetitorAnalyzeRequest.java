package com.link.linkagent.creator.competitor.model;

import jakarta.validation.constraints.Size;

/**
 * 竞品分析请求。
 * 核心竞品视频材料从数据库读取，这里只接收本次分析的侧重点。
 */
public record CreatorCompetitorAnalyzeRequest(
        @Size(max = 2000, message = "自定义竞品分析指导长度不能超过2000个字符")
        String customGuidance,

        @Size(max = 500, message = "分析重点长度不能超过500个字符")
        String analysisFocus,

        @Size(max = 500, message = "额外要求长度不能超过500个字符")
        String extraRequirement
) {
}
