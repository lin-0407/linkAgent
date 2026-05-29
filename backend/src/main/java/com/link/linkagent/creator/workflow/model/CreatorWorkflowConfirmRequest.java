package com.link.linkagent.creator.workflow.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatorWorkflowConfirmRequest(
        @NotBlank(message = "建议ID不能为空")
        @Size(max = 64, message = "建议ID长度不能超过64个字符")
        String suggestionId
) {
}
