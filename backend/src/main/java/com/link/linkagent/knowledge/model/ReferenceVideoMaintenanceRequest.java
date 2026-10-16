package com.link.linkagent.knowledge.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 维护接口限定单批大小，避免自动脚本一次占用整库事务。 */
public final class ReferenceVideoMaintenanceRequest {
    private ReferenceVideoMaintenanceRequest() { }
    public record Status(@NotEmpty @Size(max = 200) List<@NotNull @Pattern(regexp = "BV[0-9A-Za-z]{10}") String> bvIds) { }
    public record Backfill(@Min(0) long afterId, @Min(1) @Max(500) int limit) { }
    public record Purge(@Min(1) @Max(500) int batchSize) { }
}
