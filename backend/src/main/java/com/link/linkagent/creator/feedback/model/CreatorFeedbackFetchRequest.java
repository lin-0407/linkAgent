package com.link.linkagent.creator.feedback.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/**
 * BV 评论弹幕采集请求。
 * 参数保留为小而明确的白名单，是为了让页面的一键采集只能驱动项目内固定脚本。
 */
public record CreatorFeedbackFetchRequest(
        @NotBlank(message = "BV号或视频链接不能为空")
        @Size(max = 200, message = "BV号或视频链接长度不能超过200个字符")
        String bvInput,

        @Min(value = 0, message = "主楼评论数不能小于0")
        @Max(value = 500, message = "主楼评论数不能超过500")
        Integer maxComments,

        @Min(value = 0, message = "每条评论回复数不能小于0")
        @Max(value = 100, message = "每条评论回复数不能超过100")
        Integer maxRepliesPerComment,

        @Min(value = 0, message = "弹幕数不能小于0")
        @Max(value = 2000, message = "弹幕数不能超过2000")
        Integer maxDanmaku,

        @Pattern(regexp = "json|both", message = "输出格式只支持json或both")
        String format
) {

    public CreatorFeedbackFetchRequest {
        maxComments = maxComments == null ? 50 : maxComments;
        maxRepliesPerComment = maxRepliesPerComment == null ? 20 : maxRepliesPerComment;
        maxDanmaku = maxDanmaku == null ? 500 : maxDanmaku;
        format = format == null || format.isBlank() ? "both" : format.trim().toLowerCase(Locale.ROOT);
    }
}
