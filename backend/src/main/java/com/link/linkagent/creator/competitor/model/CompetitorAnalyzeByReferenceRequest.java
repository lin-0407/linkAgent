package com.link.linkagent.creator.competitor.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 基于参考案例触发竞品分析的请求。
 * <p>
 * 参考案例本身已经由 BV 导入链路采集并清洗，因此这里不再接收竞品文稿，避免用户重复录入同一份素材。
 */
public record CompetitorAnalyzeByReferenceRequest(
        @NotBlank(message = "参考案例ID不能为空")
        @Size(max = 64, message = "参考案例ID长度不能超过64个字符")
        String referenceVideoId,

        @Size(max = 2000, message = "自定义竞品分析指导长度不能超过2000个字符")
        String customGuidance,

        @Size(max = 500, message = "分析重点长度不能超过500个字符")
        String analysisFocus,

        @Size(max = 500, message = "额外要求长度不能超过500个字符")
        String extraRequirement
) {

    /**
     * 复用原有竞品分析请求对象，确保手动竞品和参考案例竞品共用同一套 prompt 变量。
     */
    public CreatorCompetitorAnalyzeRequest toAnalyzeRequest() {
        return new CreatorCompetitorAnalyzeRequest(customGuidance, analysisFocus, extraRequirement);
    }
}
