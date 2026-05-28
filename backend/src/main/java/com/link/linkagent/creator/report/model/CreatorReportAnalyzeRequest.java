package com.link.linkagent.creator.report.model;

import jakarta.validation.constraints.Size;

/**
 * 创作复盘报告生成请求。
 * 核心数据从已保存的任务、发布前建议和反馈报告读取，这里只接收本次复盘的业务侧重点。
 */
public record CreatorReportAnalyzeRequest(
        @Size(max = 2000, message = "自定义复盘指导长度不能超过2000个字符")
        String customGuidance,

        @Size(max = 500, message = "复盘重点长度不能超过500个字符")
        String reviewFocus,

        @Size(max = 500, message = "额外要求长度不能超过500个字符")
        String extraRequirement
) {
}
