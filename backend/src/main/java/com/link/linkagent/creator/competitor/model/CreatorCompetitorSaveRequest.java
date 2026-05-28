package com.link.linkagent.creator.competitor.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 竞品视频保存请求。
 * 这里只接收用户主动提供的 BV 号、视频名称和分析文本，不做自动抓取。
 */
public record CreatorCompetitorSaveRequest(
        @NotBlank(message = "竞品BV号不能为空")
        @Pattern(regexp = "^BV[0-9A-Za-z]{10}$", message = "竞品BV号格式不正确")
        @Size(max = 12, message = "竞品BV号长度不能超过12个字符")
        String competitorBvId,

        @NotBlank(message = "竞品视频名称不能为空")
        @Size(max = 200, message = "竞品视频名称长度不能超过200个字符")
        String competitorVideoName,

        @Size(max = 128, message = "同类型视频分类长度不能超过128个字符")
        String category,

        @NotBlank(message = "竞品分析文本不能为空")
        @Size(max = 20000, message = "竞品分析文本长度不能超过20000个字符")
        String competitorSamples,

        @Size(max = 500, message = "对比维度长度不能超过500个字符")
        String compareDimension,

        @Size(max = 500, message = "补充背景长度不能超过500个字符")
        String extraContext
) {
}
