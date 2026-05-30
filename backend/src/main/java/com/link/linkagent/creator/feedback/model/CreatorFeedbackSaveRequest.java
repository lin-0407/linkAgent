package com.link.linkagent.creator.feedback.model;

import com.link.linkagent.util.TextUtil;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * 评论弹幕样例保存请求。
 * 这个入口只负责手动粘贴样例，BV 拉取和文件导入走独立接口，避免一个 DTO 承担过多职责。
 */
public record CreatorFeedbackSaveRequest(
        @Size(max = 20000, message = "评论样例长度不能超过20000个字符")
        String commentSamples,

        @Size(max = 20000, message = "弹幕样例长度不能超过20000个字符")
        String danmakuSamples,

        @Size(max = 500, message = "补充背景长度不能超过500个字符")
        String extraContext
) {

    @AssertTrue(message = "评论样例和弹幕样例至少填写一项")
    public boolean isAnyFeedbackProvided() {
        return TextUtil.hasText(commentSamples) || TextUtil.hasText(danmakuSamples);
    }
}
