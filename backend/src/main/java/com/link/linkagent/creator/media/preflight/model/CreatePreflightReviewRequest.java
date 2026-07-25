package com.link.linkagent.creator.media.preflight.model;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 发布前试映启动参数。 */
public record CreatePreflightReviewRequest(
        @NotBlank(message = "成片版本ID不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "成片版本ID格式不正确")
        String versionId,

        @AssertTrue(message = "必须确认媒体将提交给云端 Provider 处理")
        boolean confirmedProviderDisclosure,

        @Size(max = 1000, message = "试映重点不能超过1000个字符")
        String reviewFocus
) {
}
