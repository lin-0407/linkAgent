package com.link.linkagent.creator.context.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增语境词条请求。
 * sourceType 必须由前端明确传入，是为了区分用户主动保存和系统候选提取，后续权重策略才有依据。
 */
public record CreatorContextTermCreateRequest(
        @Size(max = 64, message = "用户ID长度不能超过64个字符")
        String userId,

        @NotBlank(message = "视频类型不能为空")
        @Size(max = 64, message = "视频类型长度不能超过64个字符")
        String videoType,

        @NotBlank(message = "词条不能为空")
        @Size(max = 128, message = "词条长度不能超过128个字符")
        String term,

        @Pattern(
                regexp = "KEYWORD|SLANG|MEME|TABOO|TITLE_PATTERN|AUDIENCE_CONCERN",
                message = "词条类型只能是 KEYWORD、SLANG、MEME、TABOO、TITLE_PATTERN 或 AUDIENCE_CONCERN"
        )
        String termType,

        @Pattern(
                regexp = "POSITIVE|NEGATIVE|NEUTRAL",
                message = "词条倾向只能是 POSITIVE、NEGATIVE 或 NEUTRAL"
        )
        String polarity,

        @Pattern(
                regexp = "USER_SAVE|AI_ACCEPTED|COMMENT_EXTRACTED|USER_REJECTED|VIDEO_SUCCESS",
                message = "来源类型只能是 USER_SAVE、AI_ACCEPTED、COMMENT_EXTRACTED、USER_REJECTED 或 VIDEO_SUCCESS"
        )
        String sourceType,

        @Size(max = 64, message = "来源任务ID长度不能超过64个字符")
        String sourceTaskId,

        @Size(max = 1000, message = "证据说明长度不能超过1000个字符")
        String evidenceText
) {
}
