package com.link.linkagent.creator.production.mapper;

import com.link.linkagent.creator.production.model.ProductionPlanRecord;
import com.link.linkagent.creator.production.model.ProductionStepRecord;
import com.link.linkagent.creator.production.model.ToolCatalogRecord;
import com.link.linkagent.creator.production.model.ToolKnowledgeRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * 阶段 7 P0-1 制作蓝图访问层。
 * 所有任务级查询都携带 ownerId，避免未来扩展部署方式时遗漏归属校验。
 */
@Mapper
public interface ProductionPlanMapper {

    @Select("""
            SELECT COUNT(1)
            FROM creator_task
            WHERE task_id = #{taskId}
              AND user_id = #{ownerId}
              AND is_deleted = 0
            """)
    int countTaskByOwner(@Param("taskId") String taskId, @Param("ownerId") String ownerId);

    @Select("""
            SELECT CASE WHEN
                EXISTS (
                    SELECT 1
                    FROM creator_task_video_binding
                    WHERE task_id = #{taskId}
                      AND user_id = #{ownerId}
                      AND binding_status = 'BOUND'
                      AND is_deleted = 0
                )
                OR EXISTS (
                    SELECT 1
                    FROM creator_draft_video
                    WHERE task_id = #{taskId}
                      AND owner_id = #{ownerId}
                      AND published_flag = 1
                      AND is_deleted = 0
                )
                THEN 1 ELSE 0 END
            """)
    int countFinalizedPublishingFacts(@Param("taskId") String taskId,
                                      @Param("ownerId") String ownerId);

    /**
     * 重新定位后旧的待校验 BV 已不再对应当前成片；逻辑删除记录也必须硬删除，
     * 因为任务唯一键不包含 is_deleted，残留行仍会阻止后续绑定新 BV。
     */
    @Delete("""
            DELETE FROM creator_task_video_binding
            WHERE task_id = #{taskId}
              AND user_id = #{ownerId}
              AND (
                  is_deleted = 1
                  OR binding_status IS NULL
                  OR binding_status <> 'BOUND'
              )
            """)
    int deleteUnboundVideoBindingsForReposition(@Param("taskId") String taskId,
                                                 @Param("ownerId") String ownerId);

    @Update("""
            UPDATE creator_media_upload
            SET status = 'SUPERSEDED',
                failure_message = '制作蓝图已重新定位，请重新上传成片',
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND status <> 'SUPERSEDED'
              AND is_deleted = 0
            """)
    int invalidateMediaUploads(@Param("taskId") String taskId,
                               @Param("ownerId") String ownerId);

    @Update("""
            UPDATE creator_media_processing_job
            SET status = 'FAILED',
                failure_message = '制作蓝图已重新定位，原处理任务已失效',
                is_deleted = 1,
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND is_deleted = 0
            """)
    int invalidateMediaProcessingJobs(@Param("taskId") String taskId,
                                      @Param("ownerId") String ownerId);

    @Update("""
            UPDATE creator_preflight_review
            SET status = 'CANCELLED',
                cancel_requested = 1,
                is_deleted = 1,
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND is_deleted = 0
            """)
    int invalidatePreflightReviews(@Param("taskId") String taskId,
                                   @Param("ownerId") String ownerId);

    @Update("""
            UPDATE creator_draft_video
            SET duration_ms = NULL,
                width = NULL,
                height = NULL,
                frame_rate = NULL,
                video_codec = NULL,
                audio_codec = NULL,
                has_audio = NULL,
                probe_attempt_id = NULL,
                current_review_id = NULL,
                status = 'UPLOAD_ABORTED',
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND is_deleted = 0
            """)
    int resetDraftVideosForReposition(@Param("taskId") String taskId,
                                      @Param("ownerId") String ownerId);

    /**
     * 只回退已经进入反馈或复盘的任务，避免把 DRAFT 等更早状态错误推进到发布前完成。
     */
    @Update("""
            UPDATE creator_task
            SET status = 'PRE_PUBLISH_ANALYZED',
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
              AND user_id = #{ownerId}
              AND status IN (
                  'FEEDBACK_COLLECTING',
                  'FEEDBACK_ANALYZED',
                  'COMPETITOR_ANALYZED',
                  'ANALYZED'
              )
              AND is_deleted = 0
            """)
    int resetTaskStatusForReposition(@Param("taskId") String taskId,
                                     @Param("ownerId") String ownerId);

    @Select("""
            SELECT COALESCE(MAX(plan_version), 0)
            FROM creator_production_plan
            WHERE task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND is_deleted = 0
            """)
    int findMaxPlanVersion(@Param("taskId") String taskId, @Param("ownerId") String ownerId);

    @Insert("""
            INSERT INTO creator_production_plan (
                plan_id, task_id, owner_id, plan_version, video_category, production_method,
                target_audience, core_promise, target_duration_ms, available_assets,
                constraints_json, tool_preferences, source_snapshot, plan_title,
                positioning_summary, status, raw_output, prompt_version, idempotency_key
            ) VALUES (
                #{planId}, #{taskId}, #{ownerId}, #{planVersion}, #{videoCategory}, #{productionMethod},
                #{targetAudience}, #{corePromise}, #{targetDurationMs}, #{availableAssets},
                #{constraintsJson}, #{toolPreferences}, #{sourceSnapshot}, #{planTitle},
                #{positioningSummary}, #{status}, #{rawOutput}, #{promptVersion}, #{idempotencyKey}
            )
            """)
    int insertPlan(ProductionPlanRecord record);

    @Update("""
            UPDATE creator_production_plan
            SET status = 'STALE', update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND status IN ('GENERATING', 'READY', 'FAILED')
              AND is_deleted = 0
            """)
    int markCurrentPlansStale(@Param("taskId") String taskId, @Param("ownerId") String ownerId);

    @Update("""
            UPDATE creator_production_plan
            SET plan_title = #{planTitle},
                positioning_summary = #{positioningSummary},
                tool_preferences = #{toolPreferences},
                source_snapshot = #{sourceSnapshot},
                raw_output = #{rawOutput},
                prompt_version = #{promptVersion},
                status = 'READY',
                update_time = CURRENT_TIMESTAMP
            WHERE plan_id = #{planId}
              AND status = 'GENERATING'
              AND is_deleted = 0
            """)
    int markPlanReady(@Param("planId") String planId,
                      @Param("planTitle") String planTitle,
                      @Param("positioningSummary") String positioningSummary,
                      @Param("toolPreferences") String toolPreferences,
                      @Param("sourceSnapshot") String sourceSnapshot,
                      @Param("rawOutput") String rawOutput,
                      @Param("promptVersion") String promptVersion);

    @Update("""
            UPDATE creator_production_plan
            SET status = 'FAILED', raw_output = #{failureMessage}, update_time = CURRENT_TIMESTAMP
            WHERE plan_id = #{planId}
              AND status = 'GENERATING'
              AND is_deleted = 0
            """)
    int markPlanFailed(@Param("planId") String planId, @Param("failureMessage") String failureMessage);

    @Select("""
            SELECT id, plan_id, task_id, owner_id, plan_version, video_category, production_method,
                   target_audience, core_promise, target_duration_ms, available_assets,
                   constraints_json, tool_preferences, source_snapshot, plan_title,
                   positioning_summary, status, raw_output, prompt_version, idempotency_key,
                   create_time, update_time
            FROM creator_production_plan
            WHERE task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND status <> 'STALE'
              AND is_deleted = 0
            ORDER BY plan_version DESC, id DESC
            LIMIT 1
            """)
    Optional<ProductionPlanRecord> findCurrentPlan(@Param("taskId") String taskId,
                                                   @Param("ownerId") String ownerId);

    @Select("""
            SELECT id, plan_id, task_id, owner_id, plan_version, video_category, production_method,
                   target_audience, core_promise, target_duration_ms, available_assets,
                   constraints_json, tool_preferences, source_snapshot, plan_title,
                   positioning_summary, status, raw_output, prompt_version, idempotency_key,
                   create_time, update_time
            FROM creator_production_plan
            WHERE plan_id = #{planId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND is_deleted = 0
            LIMIT 1
            """)
    Optional<ProductionPlanRecord> findPlan(@Param("taskId") String taskId,
                                            @Param("ownerId") String ownerId,
                                            @Param("planId") String planId);

    @Insert("""
            INSERT INTO creator_production_step (
                step_id, plan_id, task_id, sequence_no, phase, step_name, objective,
                prerequisites, operations_json, tool_refs, expected_outputs,
                acceptance_criteria, difficulty, required_flag, status, row_version, skip_reason
            ) VALUES (
                #{stepId}, #{planId}, #{taskId}, #{sequenceNo}, #{phase}, #{stepName}, #{objective},
                #{prerequisites}, #{operationsJson}, #{toolRefs}, #{expectedOutputs},
                #{acceptanceCriteria}, #{difficulty}, #{requiredFlag}, #{status}, #{rowVersion}, #{skipReason}
            )
            """)
    int insertStep(ProductionStepRecord record);

    @Select("""
            SELECT id, step_id, plan_id, task_id, sequence_no, phase, step_name, objective,
                   prerequisites, operations_json, tool_refs, expected_outputs,
                   acceptance_criteria, difficulty, required_flag, status, row_version,
                   skip_reason, create_time, update_time
            FROM creator_production_step
            WHERE plan_id = #{planId}
              AND task_id = #{taskId}
              AND is_deleted = 0
            ORDER BY sequence_no ASC, id ASC
            """)
    List<ProductionStepRecord> listSteps(@Param("taskId") String taskId, @Param("planId") String planId);

    @Update("""
            UPDATE creator_production_step
            SET status = #{status},
                skip_reason = #{skipReason},
                row_version = row_version + 1,
                update_time = CURRENT_TIMESTAMP
            WHERE step_id = #{stepId}
              AND plan_id = #{planId}
              AND task_id = #{taskId}
              AND row_version = #{rowVersion}
              AND is_deleted = 0
            """)
    int updateStepStatus(@Param("taskId") String taskId,
                         @Param("planId") String planId,
                         @Param("stepId") String stepId,
                         @Param("status") String status,
                         @Param("skipReason") String skipReason,
                         @Param("rowVersion") long rowVersion);

    @Select("""
            SELECT COUNT(1)
            FROM creator_production_step
            WHERE plan_id = #{planId}
              AND task_id = #{taskId}
              AND status NOT IN ('COMPLETED', 'SKIPPED')
              AND is_deleted = 0
            """)
    int countIncompleteSteps(@Param("taskId") String taskId, @Param("planId") String planId);

    @Select("""
            SELECT id, tool_id, tool_name, normalized_name, official_domain, official_url,
                   capability_types, supported_categories, pricing_type, region_note,
                   default_rank, enabled, source_updated_at, create_time, update_time
            FROM creator_tool_catalog
            WHERE enabled = 1 AND is_deleted = 0
            ORDER BY default_rank ASC, id ASC
            """)
    List<ToolCatalogRecord> listEnabledTools();

    @Select("""
            SELECT id, tool_id, tool_name, normalized_name, official_domain, official_url,
                   capability_types, supported_categories, pricing_type, region_note,
                   default_rank, enabled, source_updated_at, create_time, update_time
            FROM creator_tool_catalog
            WHERE normalized_name = #{normalizedName}
              AND enabled = 1
              AND is_deleted = 0
            LIMIT 1
            """)
    Optional<ToolCatalogRecord> findToolByNormalizedName(@Param("normalizedName") String normalizedName);

    @Select("""
            SELECT id, knowledge_id, tool_id, tool_name, tool_version, official_domain,
                   source_urls, source_hash, capability_snapshot, operation_snapshot,
                   verification_status, verified_at, expires_at, raw_summary, create_time, update_time
            FROM creator_tool_knowledge
            WHERE tool_id = #{toolId}
              AND tool_version = #{toolVersion}
              AND verification_status = 'VERIFIED'
              AND expires_at > CURRENT_TIMESTAMP
              AND is_deleted = 0
            ORDER BY verified_at DESC, id DESC
            LIMIT 1
            """)
    Optional<ToolKnowledgeRecord> findCurrentKnowledge(@Param("toolId") String toolId,
                                                       @Param("toolVersion") String toolVersion);

    @Insert("""
            INSERT INTO creator_tool_knowledge (
                knowledge_id, tool_id, tool_name, tool_version, official_domain,
                source_urls, source_hash, capability_snapshot, operation_snapshot,
                verification_status, verified_at, expires_at, raw_summary
            ) VALUES (
                #{knowledgeId}, #{toolId}, #{toolName}, #{toolVersion}, #{officialDomain},
                #{sourceUrls}, #{sourceHash}, #{capabilitySnapshot}, #{operationSnapshot},
                #{verificationStatus}, #{verifiedAt}, #{expiresAt}, #{rawSummary}
            )
            """)
    int insertKnowledge(ToolKnowledgeRecord record);
}
