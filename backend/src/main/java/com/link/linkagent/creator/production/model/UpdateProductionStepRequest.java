package com.link.linkagent.creator.production.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 修改步骤状态的请求；rowVersion 用于阻止旧页面覆盖新状态。 */
public record UpdateProductionStepRequest(
        @NotNull
        ProductionStepStatus status,
        @Size(max = 500)
        String skipReason,
        @NotNull
        @Min(0)
        Long rowVersion
) {
}
