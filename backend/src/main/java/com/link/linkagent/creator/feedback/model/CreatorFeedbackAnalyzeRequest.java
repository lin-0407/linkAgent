package com.link.linkagent.creator.feedback.model;

import jakarta.validation.constraints.Size;

/**
 * 评论弹幕分析请求。
 * 核心样例从数据库读取，这里只接收本次分析的业务侧重点，避免暴露系统规则。
 */
public record CreatorFeedbackAnalyzeRequest(
        @Size(max = 2000, message = "自定义分析指导长度不能超过2000个字符")
        String customGuidance,

        @Size(max = 500, message = "分析重点长度不能超过500个字符")
        String analysisFocus,

        @Size(max = 500, message = "额外要求长度不能超过500个字符")
        String extraRequirement
) {
}
