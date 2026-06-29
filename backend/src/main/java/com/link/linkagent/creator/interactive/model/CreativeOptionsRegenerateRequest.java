package com.link.linkagent.creator.interactive.model;

import jakarta.validation.constraints.Size;

/**
 * 创意卡片重新生成请求。
 * 额外要求只作为本轮微调约束，不覆盖用户最初的创作想法。
 */
public record CreativeOptionsRegenerateRequest(
        @Size(max = 2000, message = "补充要求长度不能超过2000个字符")
        String extraRequirement
) {
}
