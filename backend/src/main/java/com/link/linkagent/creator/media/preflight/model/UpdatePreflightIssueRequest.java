package com.link.linkagent.creator.media.preflight.model;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 作者接受问题或说明暂不采纳原因。 */
public record UpdatePreflightIssueRequest(
        @NotBlank(message = "问题处置不能为空")
        @Pattern(regexp = "ACCEPTED|IGNORED", message = "问题处置只能是ACCEPTED或IGNORED")
        String disposition,

        @Size(max = 500, message = "暂不采纳原因长度不能超过500个字符")
        String reason
) {
    @AssertTrue(message = "暂不采纳时请填写原因")
    public boolean isReasonValid() {
        return !"IGNORED".equals(disposition) || (reason != null && !reason.isBlank());
    }
}
