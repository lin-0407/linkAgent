package com.link.linkagent.creator.creation.model;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 运行、预算与调用流水的最小契约，原始输入和执行租约不进入页面响应。 */
public final class CreationRuntimeModels {
    private CreationRuntimeModels() { }

    public record Workspace(String taskId, String ownerId, long workspaceRevision, String activeRunId,
                            BigDecimal budgetLimitCny, String budgetInitKey, String budgetInitHash) { }
    public record Approval(String approvalId, String taskId, String ownerId, String approvalKind,
            String inputFingerprint, BigDecimal maxAdditionalCostCny, String idempotencyKey,
            String requestHash, String status, LocalDateTime approvedAt) { }
    public record Run(String runId, String taskId, String ownerId, String kind, String status, String stage,
            String parentRunId, String approvalId, BigDecimal authorizationLimitCny, String inputFingerprint,
            String requestJson, String idempotencyKey, String requestHash, long runRevision,
            String leaseOwner, long leaseGeneration, LocalDateTime leaseExpiresAt, String dependencyId,
            int attemptCount, String resultJson, String errorCode, LocalDateTime startedAt,
            LocalDateTime completedAt, LocalDateTime createTime) { }
    public record Charge(String entryId, String chargeKey, String taskId, String ownerId, String runId,
            String approvalId, long leaseGeneration, String capability, BigDecimal reservedCny,
            BigDecimal knownCostCny, String status, String settlementKind, String evidenceRef,
            String priceSnapshotJson, LocalDateTime sentAt) { }
    public record Totals(BigDecimal knownCny, BigDecimal reservedCny, BigDecimal unknownCny, int overrunCount) {
        public BigDecimal occupied() { return knownCny.add(reservedCny).add(unknownCny); }
    }
    public record Budget(BigDecimal limitCny, BigDecimal knownCny, BigDecimal reservedCny,
            BigDecimal unknownCny, BigDecimal remainingCny, boolean paidCallsBlocked) { }
    public record RunView(String runId, String kind, String status, String stage, String parentRunId,
            long runRevision, int attemptCount, String errorCode, LocalDateTime startedAt, LocalDateTime completedAt) {
        public static RunView from(Run run) {
            return new RunView(run.runId(), run.kind(), run.status(), run.stage(), run.parentRunId(),
                    run.runRevision(), run.attemptCount(), run.errorCode(), run.startedAt(), run.completedAt());
        }
    }
    public record Snapshot(boolean initialized, long workspaceRevision, Budget budget,
            RunView activeRun, boolean executionAvailable, String budgetScope) { }
    public record InitializeBudgetRequest(
            @NotNull @Min(-1) Long expectedWorkspaceRevision,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal budgetLimitCny) { }
    public record UpdateBudgetRequest(
            @NotNull @Min(0) Long expectedWorkspaceRevision,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal budgetLimitCny) { }
}
