package com.link.linkagent.creator.creation.mapper;

import com.link.linkagent.creator.creation.model.CreationRuntimeModels.*;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.Optional;

@Mapper
public interface CreationRuntimeMapper {
    String RUN_COLUMNS = "run_id, task_id, owner_id, kind, status, stage, parent_run_id, approval_id, authorization_limit_cny, input_fingerprint, request_json, idempotency_key, request_hash, run_revision, lease_owner, lease_generation, lease_expires_at, dependency_id, attempt_count, result_json, error_code, started_at, completed_at, create_time";
    String CHARGE_COLUMNS = "entry_id, charge_key, task_id, owner_id, run_id, approval_id, lease_generation, capability, reserved_cny, known_cost_cny, status, settlement_kind, evidence_ref, price_snapshot_json, sent_at";
    String TOTALS = """
            SELECT COALESCE(SUM(IF(status='SETTLED', known_cost_cny, 0)), 0) AS known_cny,
                COALESCE(SUM(IF(status='RESERVED', reserved_cny, 0)), 0) AS reserved_cny,
                COALESCE(SUM(IF(status='UNKNOWN', reserved_cny, 0)), 0) AS unknown_cny,
                COALESCE(SUM(IF(status='SETTLED' AND known_cost_cny > reserved_cny, 1, 0)), 0) AS overrun_count
            FROM creator_creation_cost_entry WHERE task_id=#{taskId} AND owner_id=#{ownerId}
            """;

    @Select("SELECT task_id, owner_id, workspace_revision, active_run_id, budget_limit_cny, budget_init_key, budget_init_hash FROM creator_creation_workspace WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND is_deleted=0")
    Optional<Workspace> workspace(@Param("taskId") String taskId, @Param("ownerId") String ownerId);

    @Update("UPDATE creator_creation_workspace SET budget_limit_cny=#{amount}, budget_init_key=#{key}, budget_init_hash=#{hash}, workspace_revision=workspace_revision+1 WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND budget_limit_cny IS NULL AND workspace_revision=#{revision} AND is_deleted=0")
    int initialize(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("amount") BigDecimal amount,
                   @Param("key") String key, @Param("hash") String hash, @Param("revision") long revision);

    @Update("UPDATE creator_creation_workspace SET budget_limit_cny=#{amount}, workspace_revision=workspace_revision+1 WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND workspace_revision=#{revision} AND is_deleted=0")
    int budget(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("amount") BigDecimal amount, @Param("revision") long revision);

    @Update("UPDATE creator_creation_workspace SET workspace_revision=workspace_revision+1 WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND workspace_revision=#{revision} AND is_deleted=0")
    int bump(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("revision") long revision);

    @Update("UPDATE creator_creation_workspace SET active_run_id=#{runId}, workspace_revision=workspace_revision+1 WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND active_run_id IS NULL AND workspace_revision=#{revision} AND is_deleted=0")
    int activate(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId, @Param("revision") long revision);

    @Update("UPDATE creator_creation_workspace SET active_run_id=NULL, workspace_revision=workspace_revision+1 WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND active_run_id=#{runId} AND is_deleted=0")
    int deactivate(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId);

    @Select("SELECT * FROM creator_creation_approval WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND idempotency_key=#{key}")
    Optional<Approval> approvalByKey(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("key") String key);
    @Select("SELECT * FROM creator_creation_approval WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND approval_id=#{approvalId}")
    Optional<Approval> approval(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("approvalId") String approvalId);
    @Insert("INSERT INTO creator_creation_approval (approval_id, task_id, owner_id, approval_kind, input_fingerprint, max_additional_cost_cny, idempotency_key, request_hash) VALUES (#{approvalId}, #{taskId}, #{ownerId}, #{approvalKind}, #{inputFingerprint}, #{maxAdditionalCostCny}, #{idempotencyKey}, #{requestHash})")
    int insertApproval(Approval approval);
    @Update("UPDATE creator_creation_approval SET status='REVOKED' WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND approval_id=#{approvalId} AND status='ACTIVE'")
    int revoke(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("approvalId") String approvalId);

    @Select("SELECT " + RUN_COLUMNS + " FROM creator_creation_run WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId}")
    Optional<Run> run(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId);
    @Select("SELECT " + RUN_COLUMNS + " FROM creator_creation_run WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND idempotency_key=#{key}")
    Optional<Run> runByKey(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("key") String key);
    @Insert("""
            INSERT INTO creator_creation_run (run_id, task_id, owner_id, kind, parent_run_id, approval_id,
                authorization_limit_cny, input_fingerprint, request_json, idempotency_key, request_hash)
            VALUES (#{runId}, #{taskId}, #{ownerId}, #{kind}, #{parentRunId}, #{approvalId},
                #{authorizationLimitCny}, #{inputFingerprint}, #{requestJson}, #{idempotencyKey}, #{requestHash})
            """)
    int insertRun(Run run);

    @Update("""
            UPDATE creator_creation_run SET status=IF(status='CANCEL_REQUESTED','CANCEL_REQUESTED','RUNNING'),
                stage=IF(status='CANCEL_REQUESTED','CANCEL_REQUESTED','RUNNING'), lease_owner=#{worker},
                lease_generation=lease_generation+1, lease_expires_at=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP(6)),
                run_revision=run_revision+1, attempt_count=attempt_count+1, started_at=COALESCE(started_at,CURRENT_TIMESTAMP(6))
            WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId}
                AND (status='QUEUED' OR (status IN ('RUNNING','CANCEL_REQUESTED')
                    AND (lease_expires_at IS NULL OR lease_expires_at <= CURRENT_TIMESTAMP(6))))
            """)
    int claim(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId,
              @Param("worker") String worker, @Param("seconds") int seconds);

    @Select("SELECT COUNT(*) FROM creator_creation_run WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId} AND status=#{state} AND lease_owner=#{worker} AND lease_generation=#{generation} AND lease_expires_at>CURRENT_TIMESTAMP(6)")
    int liveLease(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId,
                  @Param("worker") String worker, @Param("generation") long generation, @Param("state") String state);
    @Update("UPDATE creator_creation_run SET lease_expires_at=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP(6)) WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId} AND status IN ('RUNNING','CANCEL_REQUESTED') AND lease_owner=#{worker} AND lease_generation=#{generation} AND lease_expires_at>CURRENT_TIMESTAMP(6)")
    int heartbeat(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId,
                  @Param("worker") String worker, @Param("generation") long generation, @Param("seconds") int seconds);
    @Update("""
            UPDATE creator_creation_run SET status=#{state}, stage=#{state}, dependency_id=#{dependency},
                lease_owner=NULL, lease_expires_at=NULL, run_revision=run_revision+1,
                completed_at=IF(#{state}='WAITING_DEPENDENCY',NULL,CURRENT_TIMESTAMP(6)),
                result_json=#{result}, error_code=#{error}
            WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId}
                AND status=#{expectedState} AND lease_owner=#{worker} AND lease_generation=#{generation}
                AND lease_expires_at>CURRENT_TIMESTAMP(6)
            """)
    int finish(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId,
               @Param("worker") String worker, @Param("generation") long generation, @Param("expectedState") String expectedState,
               @Param("state") String state, @Param("dependency") String dependency, @Param("result") String result, @Param("error") String error);
    @Update("UPDATE creator_creation_run SET status=IF(status='QUEUED','CANCELLED','CANCEL_REQUESTED'), stage=status, completed_at=IF(status='CANCELLED',CURRENT_TIMESTAMP(6),NULL), run_revision=run_revision+1 WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId} AND status IN ('QUEUED','RUNNING','WAITING_DEPENDENCY')")
    int cancel(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId);
    @Update("UPDATE creator_creation_run SET status='QUEUED', stage='QUEUED', dependency_id=NULL, run_revision=run_revision+1 WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId} AND status='WAITING_DEPENDENCY' AND run_revision=#{revision}")
    int wake(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId, @Param("revision") long revision);

    @Select(TOTALS)
    Totals totals(@Param("taskId") String taskId, @Param("ownerId") String ownerId);
    @Select(TOTALS + " AND approval_id=#{approvalId}")
    Totals approvalTotals(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("approvalId") String approvalId);
    @Select("SELECT " + CHARGE_COLUMNS + " FROM creator_creation_cost_entry WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND charge_key=#{key}")
    Optional<Charge> charge(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("key") String key);
    @Insert("INSERT INTO creator_creation_cost_entry (entry_id,charge_key,task_id,owner_id,run_id,approval_id,lease_generation,capability,reserved_cny,price_snapshot_json) VALUES (#{entryId},#{chargeKey},#{taskId},#{ownerId},#{runId},#{approvalId},#{leaseGeneration},#{capability},#{reservedCny},#{priceSnapshotJson})")
    int insertCharge(Charge charge);
    @Update("UPDATE creator_creation_cost_entry SET sent_at=CURRENT_TIMESTAMP(6) WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND charge_key=#{key} AND status='RESERVED' AND sent_at IS NULL")
    int sent(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("key") String key);
    @Update("UPDATE creator_creation_cost_entry SET status='SETTLED', known_cost_cny=#{actual}, settlement_kind=#{kind}, evidence_ref=#{evidence} WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND charge_key=#{key} AND status IN ('RESERVED','UNKNOWN') AND sent_at IS NOT NULL")
    int settle(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("key") String key,
               @Param("actual") BigDecimal actual, @Param("kind") String kind, @Param("evidence") String evidence);
    @Update("UPDATE creator_creation_cost_entry SET status='UNKNOWN', evidence_ref='USAGE_UNKNOWN' WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND charge_key=#{key} AND status='RESERVED' AND sent_at IS NOT NULL")
    int unknown(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("key") String key);
    @Update("UPDATE creator_creation_cost_entry SET status=IF(sent_at IS NULL,'RELEASED','UNKNOWN'), evidence_ref=IF(sent_at IS NULL,'NOT_SENT','LEASE_OR_RUN_ENDED') WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId} AND status='RESERVED'")
    int closeReservations(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId);
    @Select("SELECT COUNT(*) FROM creator_creation_cost_entry WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId} AND status='RESERVED'")
    int pendingReservations(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId);

    @Update("""
            UPDATE creator_creation_run SET stage='ARTIFACT_READY',run_revision=run_revision+1
            WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId} AND status='RUNNING'
                AND lease_owner=#{worker} AND lease_generation=#{generation} AND lease_expires_at>CURRENT_TIMESTAMP(6)
            """)
    int artifactReady(com.link.linkagent.creator.creation.model.GeneratedDraftLease lease);
}
