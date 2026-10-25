package com.link.linkagent.creator.creation.execution;

import com.link.linkagent.creator.creation.model.CreationRuntimeModels.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class ExecutionModels {
    private ExecutionModels() { }
    public record Execution(String executionId, String taskId, String ownerId, String kind, String status,
            String stage, String sourceStoryboardId, String storyboardId, String sceneIdsJson,
            String publishingSessionId, String publishingResultId, String baseVersionId,
            BigDecimal maxAdditionalCostCny, String currentRunId, long expectedWorkspaceRevision,
            String idempotencyKey, String requestHash, String providerFingerprint, String errorCode, String objectsJson, LocalDateTime createTime) { }
    public record EstimateInput(@NotNull @Min(0) Long expectedWorkspaceRevision,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String storyboardId,
            @NotBlank @Pattern(regexp="SAMPLE|FULL|REVISION") String kind) { }
    public record Start(@NotNull @Min(0) Long expectedWorkspaceRevision,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String storyboardId,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String storyboardHash,
            @NotBlank @Pattern(regexp="SAMPLE|FULL|REVISION") String kind,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String providerFingerprint,
            @NotNull @DecimalMin("0") @Digits(integer=12,fraction=6) BigDecimal maxAdditionalCostCny) { }
    public record Estimate(String storyboardId, String storyboardHash, String renderContentHash, String kind, long workspaceRevision,
            List<String> sceneIds, long durationMs, List<String> voiceSceneIds, List<String> videoSceneIds,
            BigDecimal preparationEstimateCny, BigDecimal reviewEstimateCny, BigDecimal estimatedMaxAdditionalCostCny,
            String priceBasis, String providerFingerprint, List<String> blockers) { }
    public record View(String executionId, String kind, String status, String stage, String storyboardId,
            List<String> sceneIds, BigDecimal maxAdditionalCostCny, Totals costs, RunView run,
            String versionId, boolean previewAvailable, boolean packageAvailable, String errorCode,
            String message, LocalDateTime createTime) { }
    public record Output(String executionId, long generation, String fileName, String bucketName,
            String objectKey, long bytes, String sha256, String contentType, boolean adopted, boolean deleted) { }
}
