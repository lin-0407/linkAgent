package com.link.linkagent.creator.creation.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SelectVideoVersionRequest(
        @NotNull(message = "请提供工作区修订号") @Min(value = 0, message = "工作区修订号不能为负数")
        Long expectedWorkspaceRevision) {
}
