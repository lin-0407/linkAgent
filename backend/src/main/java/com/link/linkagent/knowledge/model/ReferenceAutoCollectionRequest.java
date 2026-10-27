package com.link.linkagent.knowledge.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReferenceAutoCollectionRequest(
        @DecimalMin("0") @DecimalMax("100") Double minIndex,
        @Min(1) Integer maxImports,
        @Min(1) @Max(10000) Integer maxChecks,
        @Size(max = 50) List<@NotNull @Min(1) Integer> rids
) {}
