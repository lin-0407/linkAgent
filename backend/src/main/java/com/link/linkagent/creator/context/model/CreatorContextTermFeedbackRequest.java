package com.link.linkagent.creator.context.model;

import jakarta.validation.constraints.NotNull;

/**
 * 语境词条反馈请求。
 * 只记录接受或拒绝，是为了先形成权重闭环，不在第一版引入复杂评分。
 */
public record CreatorContextTermFeedbackRequest(
        @NotNull(message = "是否接受不能为空")
        Boolean accepted
) {
}
