package com.link.linkagent.creator.workflow.model;

import jakarta.validation.constraints.Size;

public record CreatorWorkflowStartRequest(
        @Size(max = 64, message = "用户ID长度不能超过64个字符")
        String userId,

        Boolean resumeLatest
) {

    public boolean shouldResumeLatest() {
        return resumeLatest == null || resumeLatest;
    }
}
