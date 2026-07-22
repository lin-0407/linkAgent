package com.link.linkagent.creator.production.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 创建或重新生成制作蓝图的定位输入。 */
public record CreateProductionPlanRequest(
        @NotNull
        ProductionVideoCategory videoCategory,
        @NotNull
        ProductionMethod productionMethod,
        @NotBlank
        @Size(max = 1000)
        String targetAudience,
        @NotBlank
        @Size(max = 1000)
        String corePromise,
        @Min(60)
        @Max(1800)
        Integer targetDurationSeconds,
        @Size(max = 20)
        List<@NotBlank @Size(max = 255) String> availableAssets,
        @Size(max = 2000)
        String constraints,
        @Valid
        @Size(max = 10)
        List<PreferredToolRequest> preferredTools
) {
}
