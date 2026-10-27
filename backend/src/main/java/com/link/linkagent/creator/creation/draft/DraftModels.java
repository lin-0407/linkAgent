package com.link.linkagent.creator.creation.draft;

import com.link.linkagent.creator.creation.execution.ExecutionModels;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 一次点击授权制作一个完整版本；同任务旧版本与用户反馈始终保留。 */
public final class DraftModels {
    private DraftModels() { }
    public record Start(@NotNull @Min(-1) Long expectedWorkspaceRevision,
            @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String baseDraftId,
            @NotNull @Size(max=8000) String instruction,
            @NotNull @DecimalMin("0.000001") @Digits(integer=12,fraction=6) BigDecimal budgetLimitCny,
            @Min(1000) @Max(180000) long targetDurationMs,
            @NotNull @Size(max=32) List<@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String> assetIds) { }
    public record Draft(String draftId,String taskId,String ownerId,int version,String baseDraftId,
            String requestJson,String requestHash,String idempotencyKey,String status,String briefId,
            String preparationRunId,String storyboardId,String executionId,long expectedWorkspaceRevision,
            BigDecimal authorizationLimitCny,String errorMessage,int dispatchFailures,LocalDateTime createTime) { }
    public record View(String draftId,int version,String baseDraftId,String requestKey,String instruction,String status,long targetDurationMs,
            String message,String storyboardId,List<String> creativeChoices,List<String> reviewNotes,
            ExecutionModels.View execution,LocalDateTime createTime) { }
    public record Workspace(List<View> versions,CreationRuntimeModels.Snapshot runtime) { }
}
