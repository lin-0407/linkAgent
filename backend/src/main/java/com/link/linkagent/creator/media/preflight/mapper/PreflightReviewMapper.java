package com.link.linkagent.creator.media.preflight.mapper;

import com.link.linkagent.creator.media.preflight.model.AudienceScreeningRecord;
import com.link.linkagent.creator.media.preflight.model.EditTaskRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightIssueRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewRecord;
import com.link.linkagent.creator.media.preflight.model.PreflightStepRecord;
import com.link.linkagent.creator.media.preflight.model.TimelineEvidenceRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** P0-3/P0-4 发布前试映持久化访问层。 */
@Mapper
public interface PreflightReviewMapper {

    String REVIEW_COLUMNS = """
            id, review_id, task_id, version_id, owner_id, processing_job_id, idempotency_key,
            review_focus, status, current_step, progress_percent, event_sequence, cancel_requested,
            attempt_count, max_attempts, next_run_at, lease_owner, lease_expires_at,
            input_fingerprint, provider_snapshot, capability_gaps, executive_summary,
            estimated_cost_usd, actual_cost_usd, usage_seconds, currency,
            error_code, error_message, started_at, completed_at, create_time, update_time
            """;

    @Insert("""
            INSERT INTO creator_preflight_review (
                review_id, task_id, version_id, owner_id, processing_job_id, idempotency_key,
                review_focus, status, current_step, progress_percent, event_sequence,
                cancel_requested, attempt_count, max_attempts, next_run_at, input_fingerprint,
                provider_snapshot, capability_gaps, estimated_cost_usd, currency
            ) VALUES (
                #{reviewId}, #{taskId}, #{versionId}, #{ownerId}, #{processingJobId}, #{idempotencyKey},
                #{reviewFocus}, #{status}, #{currentStep}, #{progressPercent}, #{eventSequence},
                #{cancelRequested}, #{attemptCount}, #{maxAttempts}, #{nextRunAt}, #{inputFingerprint},
                #{providerSnapshot}, #{capabilityGaps}, #{estimatedCostUsd}, #{currency}
            )
            """)
    int insertReview(PreflightReviewRecord record);

    @Insert("""
            INSERT INTO creator_preflight_step (
                step_id, review_id, step_type, sequence_no, status, attempt_count, input_fingerprint
            ) VALUES (
                #{stepId}, #{reviewId}, #{stepType}, #{sequenceNo}, #{status}, #{attemptCount}, #{inputFingerprint}
            )
            """)
    int insertStep(PreflightStepRecord record);

    @Select("SELECT " + REVIEW_COLUMNS + " FROM creator_preflight_review "
            + "WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND idempotency_key = #{idempotencyKey} "
            + "AND EXISTS (SELECT 1 FROM creator_preflight_step step "
            + "WHERE step.review_id = creator_preflight_review.review_id "
            + "AND step.step_type = 'ANALYZE_VIDEO') "
            + "AND is_deleted = 0 LIMIT 1")
    Optional<PreflightReviewRecord> findByIdempotency(@Param("taskId") String taskId,
                                                       @Param("ownerId") String ownerId,
                                                       @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT " + REVIEW_COLUMNS + " FROM creator_preflight_review "
            + "WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND version_id = #{versionId} "
            + "AND status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'CANCEL_REQUESTED') "
            + "AND review_id = (SELECT current_review_id FROM creator_draft_video "
            + "WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND version_id = #{versionId} "
            + "AND is_deleted = 0 LIMIT 1) "
            + "AND EXISTS (SELECT 1 FROM creator_preflight_step step "
            + "WHERE step.review_id = creator_preflight_review.review_id "
            + "AND step.step_type = 'ANALYZE_VIDEO') "
            + "AND is_deleted = 0 ORDER BY id DESC LIMIT 1")
    Optional<PreflightReviewRecord> findActiveByVersion(@Param("taskId") String taskId,
                                                         @Param("ownerId") String ownerId,
                                                         @Param("versionId") String versionId);

    @Select("SELECT " + REVIEW_COLUMNS + " FROM creator_preflight_review "
            + "WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND version_id = #{versionId} "
            + "AND review_id = (SELECT current_review_id FROM creator_draft_video "
            + "WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND version_id = #{versionId} "
            + "AND is_deleted = 0 LIMIT 1) "
            + "AND EXISTS (SELECT 1 FROM creator_preflight_step step "
            + "WHERE step.review_id = creator_preflight_review.review_id "
            + "AND step.step_type = 'ANALYZE_VIDEO') "
            + "AND is_deleted = 0 LIMIT 1")
    Optional<PreflightReviewRecord> findCurrentByVersion(@Param("taskId") String taskId,
                                                          @Param("ownerId") String ownerId,
                                                          @Param("versionId") String versionId);

    /** 删除媒体后只关闭证据的素材入口，转写文本、问题和修改清单仍可继续查看。 */
    @Update("""
            UPDATE creator_timeline_evidence
            SET asset_available = 0
            WHERE version_id = #{versionId}
              AND asset_available = 1
              AND is_deleted = 0
            """)
    int markVersionEvidenceUnavailable(@Param("versionId") String versionId);

    @Select("""
            SELECT version_id
            FROM creator_draft_video
            WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND version_id = #{versionId}
              AND is_deleted = 0
            LIMIT 1
            FOR UPDATE
            """)
    Optional<String> lockDraftVersion(@Param("taskId") String taskId,
                                      @Param("ownerId") String ownerId,
                                      @Param("versionId") String versionId);

    @Select("SELECT " + REVIEW_COLUMNS + " FROM creator_preflight_review "
            + "WHERE review_id = #{reviewId} AND task_id = #{taskId} AND owner_id = #{ownerId} "
            + "AND is_deleted = 0 LIMIT 1")
    Optional<PreflightReviewRecord> findReview(@Param("taskId") String taskId,
                                                @Param("ownerId") String ownerId,
                                                @Param("reviewId") String reviewId);

    @Select("SELECT " + REVIEW_COLUMNS + " FROM creator_preflight_review "
            + "WHERE review_id = #{reviewId} AND status IN ('RUNNING', 'CANCEL_REQUESTED') AND lease_owner = #{leaseOwner} "
            + "AND is_deleted = 0 LIMIT 1")
    Optional<PreflightReviewRecord> findReviewForWorker(@Param("reviewId") String reviewId,
                                                         @Param("leaseOwner") String leaseOwner);

    @Select("SELECT " + REVIEW_COLUMNS + " FROM creator_preflight_review "
            + "WHERE review_id = #{reviewId} AND is_deleted = 0 LIMIT 1")
    Optional<PreflightReviewRecord> findReviewInternal(@Param("reviewId") String reviewId);

    @Select("SELECT " + REVIEW_COLUMNS + " FROM creator_preflight_review "
            + "WHERE status IN ('QUEUED', 'RETRY_WAIT') "
            + "AND (next_run_at IS NULL OR next_run_at <= CURRENT_TIMESTAMP) "
            + "AND is_deleted = 0 ORDER BY create_time ASC, id ASC LIMIT 1")
    Optional<PreflightReviewRecord> findNextRunnableReview();

    @Select("""
            SELECT id, step_id, review_id, step_type, sequence_no, status, attempt_count,
                   input_fingerprint, output_ref, provider_task_id, error_code, error_message,
                   started_at, completed_at, create_time, update_time
            FROM creator_preflight_step
            WHERE review_id = #{reviewId}
            ORDER BY sequence_no ASC, id ASC
            """)
    List<PreflightStepRecord> listSteps(@Param("reviewId") String reviewId);

    @Select("""
            SELECT id, step_id, review_id, step_type, sequence_no, status, attempt_count,
                   input_fingerprint, output_ref, provider_task_id, error_code, error_message,
                   started_at, completed_at, create_time, update_time
            FROM creator_preflight_step
            WHERE review_id = #{reviewId} AND step_type = #{stepType}
            LIMIT 1
            """)
    Optional<PreflightStepRecord> findStep(@Param("reviewId") String reviewId,
                                            @Param("stepType") String stepType);

    @Select("""
            SELECT id, evidence_id, review_id, version_id, source_type, start_ms, end_ms,
                   content, confidence, asset_id, asset_available, source_step_id, metadata_json
            FROM creator_timeline_evidence
            WHERE review_id = #{reviewId} AND is_deleted = 0
            ORDER BY start_ms ASC, end_ms ASC, id ASC
            """)
    List<TimelineEvidenceRecord> listEvidence(@Param("reviewId") String reviewId);

    @Select("""
            SELECT id, issue_id, review_id, version_id, issue_type, dimension, title, description,
                   start_ms, end_ms, severity, confidence, evidence_refs, suggested_action,
                   needs_human_review, source_types, affected_personas, user_disposition,
                   ignore_reason, create_time, update_time
            FROM creator_preflight_issue
            WHERE review_id = #{reviewId} AND is_deleted = 0
            ORDER BY FIELD(severity, 'BLOCKER', 'HIGH', 'MEDIUM', 'LOW'), start_ms ASC, id ASC
            """)
    List<PreflightIssueRecord> listIssues(@Param("reviewId") String reviewId);

    @Select("""
            SELECT id, screening_id, review_id, persona_type, persona_snapshot, overall_reaction,
                   interest_points, confusion_points, drop_risks, evidence_refs, confidence,
                   prompt_version, raw_output, create_time, update_time
            FROM creator_audience_screening
            WHERE review_id = #{reviewId} AND is_deleted = 0
            ORDER BY FIELD(persona_type, 'CASUAL', 'TARGET', 'CORE_FAN'), id ASC
            """)
    List<AudienceScreeningRecord> listAudienceScreenings(@Param("reviewId") String reviewId);

    @Select("""
            SELECT id, edit_task_id, review_id, issue_id, task_id, version_id, title, action,
                   start_ms, end_ms, priority, target_outcome, status, user_note, completed_at,
                   create_time, update_time
            FROM creator_edit_task
            WHERE review_id = #{reviewId} AND is_deleted = 0
            ORDER BY FIELD(status, 'IN_PROGRESS', 'TODO', 'IGNORED', 'COMPLETED'),
                     FIELD(priority, 'BLOCKER', 'HIGH', 'MEDIUM', 'LOW'), start_ms ASC, id ASC
            """)
    List<EditTaskRecord> listEditTasks(@Param("reviewId") String reviewId);

    @Update("""
            UPDATE creator_preflight_review
            SET status = 'RUNNING', lease_owner = #{leaseOwner}, lease_expires_at = #{leaseExpiresAt},
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP), error_code = NULL,
                error_message = NULL, event_sequence = event_sequence + 1,
                update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId}
              AND status IN ('QUEUED', 'RETRY_WAIT')
              AND (next_run_at IS NULL OR next_run_at <= CURRENT_TIMESTAMP)
              AND is_deleted = 0
            """)
    int claimReview(@Param("reviewId") String reviewId,
                    @Param("leaseOwner") String leaseOwner,
                    @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Update("""
            UPDATE creator_preflight_review
            SET lease_expires_at = #{leaseExpiresAt}, update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND status IN ('RUNNING', 'CANCEL_REQUESTED')
              AND lease_owner = #{leaseOwner} AND is_deleted = 0
            """)
    int renewLease(@Param("reviewId") String reviewId,
                   @Param("leaseOwner") String leaseOwner,
                   @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Update("""
            UPDATE creator_preflight_review
            SET status = 'QUEUED', lease_owner = NULL, lease_expires_at = NULL,
                next_run_at = CURRENT_TIMESTAMP, error_code = 'WORKER_INTERRUPTED',
                error_message = '试映进程中断，任务已按持久化状态恢复',
                event_sequence = event_sequence + 1, update_time = CURRENT_TIMESTAMP
            WHERE status = 'RUNNING' AND lease_expires_at < CURRENT_TIMESTAMP AND is_deleted = 0
            """)
    int requeueExpiredReviews();

    @Update("""
            UPDATE creator_preflight_review
            SET status = 'CANCELLED', current_step = 'DONE', completed_at = CURRENT_TIMESTAMP,
                lease_owner = NULL, lease_expires_at = NULL, next_run_at = NULL,
                event_sequence = event_sequence + 1, update_time = CURRENT_TIMESTAMP
            WHERE status = 'CANCEL_REQUESTED'
              AND (lease_expires_at IS NULL OR lease_expires_at < CURRENT_TIMESTAMP)
              AND is_deleted = 0
            """)
    int cancelExpiredRequestedReviews();

    @Update("""
            UPDATE creator_preflight_step
            SET status = 'RUNNING', attempt_count = attempt_count + 1,
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP), completed_at = NULL,
                error_code = NULL, error_message = NULL, update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND step_type = #{stepType} AND status = 'PENDING'
            """)
    int startStep(@Param("reviewId") String reviewId, @Param("stepType") String stepType);

    @Update("""
            UPDATE creator_preflight_step
            SET provider_task_id = #{providerTaskId}, output_ref = #{outputRef},
                update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND step_type = 'TRANSCRIBE'
              AND status = 'RUNNING' AND provider_task_id IS NULL
            """)
    int saveProviderTaskId(@Param("reviewId") String reviewId,
                           @Param("providerTaskId") String providerTaskId,
                           @Param("outputRef") String outputRef);

    @Insert("""
            INSERT IGNORE INTO creator_media_api_call_log (
                call_id, task_id, version_id, review_id, step_id, provider_name, model_name,
                capability, request_fingerprint, provider_task_id, estimated_cost_usd, status
            ) VALUES (
                #{callId}, #{taskId}, #{versionId}, #{reviewId}, #{stepId}, 'DASHSCOPE', #{modelName},
                'ASR', #{requestFingerprint}, #{providerTaskId}, #{estimatedCostUsd}, 'SUBMITTED'
            )
            """)
    int insertAsrCall(@Param("callId") String callId,
                      @Param("taskId") String taskId,
                      @Param("versionId") String versionId,
                      @Param("reviewId") String reviewId,
                      @Param("stepId") String stepId,
                      @Param("modelName") String modelName,
                      @Param("requestFingerprint") String requestFingerprint,
                      @Param("providerTaskId") String providerTaskId,
                      @Param("estimatedCostUsd") BigDecimal estimatedCostUsd);

    @Insert("""
            INSERT INTO creator_media_api_call_log (
                call_id, task_id, version_id, review_id, step_id, provider_name, model_name,
                capability, request_fingerprint, estimated_cost_usd, status
            ) VALUES (
                #{callId}, #{taskId}, #{versionId}, #{reviewId}, #{stepId}, 'DASHSCOPE', #{modelName},
                'VIDEO', #{requestFingerprint}, #{estimatedCostUsd}, 'SUBMITTED'
            )
            """)
    int insertVideoCall(@Param("callId") String callId,
                        @Param("taskId") String taskId,
                        @Param("versionId") String versionId,
                        @Param("reviewId") String reviewId,
                        @Param("stepId") String stepId,
                        @Param("modelName") String modelName,
                        @Param("requestFingerprint") String requestFingerprint,
                        @Param("estimatedCostUsd") BigDecimal estimatedCostUsd);

    @Insert("""
            INSERT INTO creator_media_api_call_log (
                call_id, task_id, version_id, review_id, step_id, provider_name, model_name,
                capability, request_fingerprint, status
            ) VALUES (
                #{callId}, #{taskId}, #{versionId}, #{reviewId}, #{stepId}, 'SPRING_AI', 'DEFAULT_TEXT_MODEL',
                'TEXT_SCREENING', #{requestFingerprint}, 'SUBMITTED'
            )
            """)
    int insertTextScreeningCall(@Param("callId") String callId,
                                @Param("taskId") String taskId,
                                @Param("versionId") String versionId,
                                @Param("reviewId") String reviewId,
                                @Param("stepId") String stepId,
                                @Param("requestFingerprint") String requestFingerprint);

    @Update("""
            UPDATE creator_preflight_review
            SET status = 'RETRY_WAIT', next_run_at = #{nextRunAt}, lease_owner = NULL,
                lease_expires_at = NULL, error_code = #{errorCode}, error_message = #{errorMessage},
                attempt_count = attempt_count + #{attemptIncrement},
                event_sequence = event_sequence + 1, update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND status = 'RUNNING' AND lease_owner = #{leaseOwner}
            """)
    int waitForRetry(@Param("reviewId") String reviewId,
                     @Param("leaseOwner") String leaseOwner,
                     @Param("nextRunAt") LocalDateTime nextRunAt,
                     @Param("attemptIncrement") int attemptIncrement,
                     @Param("errorCode") String errorCode,
                     @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE creator_preflight_review
            SET current_step = #{currentStep}, progress_percent = #{progressPercent},
                usage_seconds = #{usageSeconds}, actual_cost_usd = #{actualCostUsd},
                event_sequence = event_sequence + 1, update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND status = 'RUNNING' AND lease_owner = #{leaseOwner}
            """)
    int advanceReview(@Param("reviewId") String reviewId,
                      @Param("leaseOwner") String leaseOwner,
                      @Param("currentStep") String currentStep,
                      @Param("progressPercent") int progressPercent,
                      @Param("usageSeconds") Long usageSeconds,
                      @Param("actualCostUsd") BigDecimal actualCostUsd);

    @Update("""
            UPDATE creator_preflight_review
            SET executive_summary = #{summary}, update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND is_deleted = 0
            """)
    int saveExecutiveSummary(@Param("reviewId") String reviewId, @Param("summary") String summary);

    @Update("""
            UPDATE creator_preflight_step
            SET status = #{status}, output_ref = #{outputRef}, error_code = #{errorCode},
                error_message = #{errorMessage}, completed_at = CURRENT_TIMESTAMP,
                update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND step_type = #{stepType}
            """)
    int finishStep(@Param("reviewId") String reviewId,
                   @Param("stepType") String stepType,
                   @Param("status") String status,
                   @Param("outputRef") String outputRef,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE creator_media_api_call_log
            SET status = 'SUCCESS', audio_duration_ms = #{audioDurationMs},
                actual_cost_usd = #{actualCostUsd}, completed_at = CURRENT_TIMESTAMP,
                update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND provider_task_id = #{providerTaskId} AND is_deleted = 0
            """)
    int completeAsrCall(@Param("reviewId") String reviewId,
                        @Param("providerTaskId") String providerTaskId,
                        @Param("audioDurationMs") Long audioDurationMs,
                        @Param("actualCostUsd") BigDecimal actualCostUsd);

    @Update("""
            UPDATE creator_media_api_call_log
            SET status = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage},
                completed_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND provider_task_id = #{providerTaskId} AND is_deleted = 0
            """)
    int failAsrCall(@Param("reviewId") String reviewId,
                    @Param("providerTaskId") String providerTaskId,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE creator_media_api_call_log
            SET status = 'SUCCESS', input_tokens = #{inputTokens}, output_tokens = #{outputTokens},
                actual_cost_usd = #{actualCostUsd}, result_count = #{resultCount},
                completed_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
            WHERE call_id = #{callId} AND capability = 'VIDEO' AND is_deleted = 0
            """)
    int completeVideoCall(@Param("callId") String callId,
                          @Param("inputTokens") Long inputTokens,
                          @Param("outputTokens") Long outputTokens,
                          @Param("actualCostUsd") BigDecimal actualCostUsd,
                          @Param("resultCount") int resultCount);

    @Update("""
            UPDATE creator_media_api_call_log
            SET status = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage},
                completed_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
            WHERE call_id = #{callId} AND capability = 'VIDEO' AND is_deleted = 0
            """)
    int failVideoCall(@Param("callId") String callId,
                      @Param("errorCode") String errorCode,
                      @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE creator_media_api_call_log
            SET status = 'SUCCESS', input_tokens = #{inputTokens}, output_tokens = #{outputTokens},
                result_count = #{resultCount}, completed_at = CURRENT_TIMESTAMP,
                update_time = CURRENT_TIMESTAMP
            WHERE call_id = #{callId} AND capability = 'TEXT_SCREENING' AND is_deleted = 0
            """)
    int completeTextScreeningCall(@Param("callId") String callId,
                                  @Param("inputTokens") Integer inputTokens,
                                  @Param("outputTokens") Integer outputTokens,
                                  @Param("resultCount") int resultCount);

    @Update("""
            UPDATE creator_media_api_call_log
            SET status = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage},
                completed_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP
            WHERE call_id = #{callId} AND capability = 'TEXT_SCREENING' AND is_deleted = 0
            """)
    int failTextScreeningCall(@Param("callId") String callId,
                              @Param("errorCode") String errorCode,
                              @Param("errorMessage") String errorMessage);

    @Select("""
            SELECT COALESCE(SUM(actual_cost_usd), 0)
            FROM creator_media_api_call_log
            WHERE review_id = #{reviewId} AND status = 'SUCCESS' AND is_deleted = 0
            """)
    BigDecimal sumActualCost(@Param("reviewId") String reviewId);

    @Update("""
            UPDATE creator_timeline_evidence
            SET is_deleted = 1
            WHERE review_id = #{reviewId} AND source_step_id = #{sourceStepId} AND is_deleted = 0
            """)
    int deleteEvidenceByStep(@Param("reviewId") String reviewId,
                             @Param("sourceStepId") String sourceStepId);

    @Insert("""
            INSERT INTO creator_timeline_evidence (
                evidence_id, review_id, version_id, source_type, start_ms, end_ms, content,
                confidence, asset_id, asset_available, source_step_id, metadata_json
            ) VALUES (
                #{evidenceId}, #{reviewId}, #{versionId}, #{sourceType}, #{startMs}, #{endMs}, #{content},
                #{confidence}, #{assetId}, #{assetAvailable}, #{sourceStepId}, #{metadataJson}
            )
            """)
    int insertEvidence(TimelineEvidenceRecord record);

    @Update("""
            UPDATE creator_preflight_issue SET is_deleted = 1, update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND is_deleted = 0
            """)
    int deleteIssuesByReview(@Param("reviewId") String reviewId);

    @Insert("""
            INSERT INTO creator_preflight_issue (
                issue_id, review_id, version_id, issue_type, dimension, title, description,
                start_ms, end_ms, severity, confidence, evidence_refs, suggested_action,
                needs_human_review, source_types
            ) VALUES (
                #{issueId}, #{reviewId}, #{versionId}, #{issueType}, #{dimension}, #{title}, #{description},
                #{startMs}, #{endMs}, #{severity}, #{confidence}, #{evidenceRefs}, #{suggestedAction},
                #{needsHumanReview}, #{sourceTypes}
            )
            """)
    int insertIssue(PreflightIssueRecord record);

    @Update("""
            UPDATE creator_preflight_issue
            SET description = #{description}, severity = #{severity}, confidence = #{confidence},
                suggested_action = #{suggestedAction}, evidence_refs = #{evidenceRefs},
                source_types = #{sourceTypes}, update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND issue_id = #{issueId} AND is_deleted = 0
            """)
    int updateIssueAfterSegmentReview(@Param("reviewId") String reviewId,
                                      @Param("issueId") String issueId,
                                      @Param("description") String description,
                                      @Param("severity") String severity,
                                      @Param("confidence") BigDecimal confidence,
                                      @Param("suggestedAction") String suggestedAction,
                                      @Param("evidenceRefs") String evidenceRefs,
                                      @Param("sourceTypes") String sourceTypes);

    @Update("""
            UPDATE creator_audience_screening
            SET is_deleted = 1, update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND is_deleted = 0
            """)
    int deleteAudienceScreenings(@Param("reviewId") String reviewId);

    @Insert("""
            INSERT INTO creator_audience_screening (
                screening_id, review_id, persona_type, persona_snapshot, overall_reaction,
                interest_points, confusion_points, drop_risks, evidence_refs, confidence,
                prompt_version, raw_output
            ) VALUES (
                #{screeningId}, #{reviewId}, #{personaType}, #{personaSnapshot}, #{overallReaction},
                #{interestPoints}, #{confusionPoints}, #{dropRisks}, #{evidenceRefs}, #{confidence},
                #{promptVersion}, #{rawOutput}
            )
            ON DUPLICATE KEY UPDATE
                screening_id = #{screeningId}, persona_snapshot = #{personaSnapshot},
                overall_reaction = #{overallReaction}, interest_points = #{interestPoints},
                confusion_points = #{confusionPoints}, drop_risks = #{dropRisks},
                evidence_refs = #{evidenceRefs}, confidence = #{confidence},
                prompt_version = #{promptVersion}, raw_output = #{rawOutput},
                is_deleted = 0, update_time = CURRENT_TIMESTAMP
            """)
    int insertAudienceScreening(AudienceScreeningRecord record);

    @Update("""
            UPDATE creator_preflight_issue
            SET affected_personas = #{affectedPersonas}, update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND issue_id = #{issueId} AND is_deleted = 0
            """)
    int updateIssueAffectedPersonas(@Param("reviewId") String reviewId,
                                    @Param("issueId") String issueId,
                                    @Param("affectedPersonas") String affectedPersonas);

    @Select("""
            SELECT issue.id, issue.issue_id, issue.review_id, issue.version_id, issue.issue_type,
                   issue.dimension, issue.title, issue.description, issue.start_ms, issue.end_ms,
                   issue.severity, issue.confidence, issue.evidence_refs, issue.suggested_action,
                   issue.needs_human_review, issue.source_types, issue.affected_personas,
                   issue.user_disposition, issue.ignore_reason, issue.create_time, issue.update_time
            FROM creator_preflight_issue issue
            INNER JOIN creator_preflight_review review ON review.review_id = issue.review_id
            WHERE issue.issue_id = #{issueId} AND review.task_id = #{taskId}
              AND review.owner_id = #{ownerId} AND issue.is_deleted = 0 AND review.is_deleted = 0
            LIMIT 1
            """)
    Optional<PreflightIssueRecord> findIssueForOwner(@Param("taskId") String taskId,
                                                      @Param("ownerId") String ownerId,
                                                      @Param("issueId") String issueId);

    @Update("""
            UPDATE creator_preflight_issue
            SET user_disposition = #{disposition},
                ignore_reason = CASE WHEN #{disposition} = 'IGNORED' THEN #{reason} ELSE NULL END,
                update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND issue_id = #{issueId} AND is_deleted = 0
            """)
    int updateIssueDisposition(@Param("reviewId") String reviewId,
                               @Param("issueId") String issueId,
                               @Param("disposition") String disposition,
                               @Param("reason") String reason);

    @Insert("""
            INSERT INTO creator_edit_task (
                edit_task_id, review_id, issue_id, task_id, version_id, title, action,
                start_ms, end_ms, priority, target_outcome, status
            ) VALUES (
                #{editTaskId}, #{reviewId}, #{issueId}, #{taskId}, #{versionId}, #{title}, #{action},
                #{startMs}, #{endMs}, #{priority}, #{targetOutcome}, 'TODO'
            )
            ON DUPLICATE KEY UPDATE
                user_note = CASE WHEN status = 'IGNORED' THEN NULL ELSE user_note END,
                status = CASE WHEN status = 'IGNORED' THEN 'TODO' ELSE status END,
                is_deleted = 0, update_time = CURRENT_TIMESTAMP
            """)
    int upsertEditTask(@Param("editTaskId") String editTaskId,
                       @Param("reviewId") String reviewId,
                       @Param("issueId") String issueId,
                       @Param("taskId") String taskId,
                       @Param("versionId") String versionId,
                       @Param("title") String title,
                       @Param("action") String action,
                       @Param("startMs") Long startMs,
                       @Param("endMs") Long endMs,
                       @Param("priority") String priority,
                       @Param("targetOutcome") String targetOutcome);

    @Update("""
            UPDATE creator_edit_task
            SET status = 'IGNORED', user_note = #{reason}, completed_at = NULL,
                update_time = CURRENT_TIMESTAMP
            WHERE issue_id = #{issueId} AND is_deleted = 0
            """)
    int ignoreEditTaskByIssue(@Param("issueId") String issueId, @Param("reason") String reason);

    @Select("""
            SELECT id, edit_task_id, review_id, issue_id, task_id, version_id, title, action,
                   start_ms, end_ms, priority, target_outcome, status, user_note, completed_at,
                   create_time, update_time
            FROM creator_edit_task
            WHERE edit_task_id = #{editTaskId} AND task_id = #{taskId} AND is_deleted = 0
              AND EXISTS (SELECT 1 FROM creator_preflight_review review
                          WHERE review.review_id = creator_edit_task.review_id
                            AND review.owner_id = #{ownerId} AND review.is_deleted = 0)
            LIMIT 1
            """)
    Optional<EditTaskRecord> findEditTaskForOwner(@Param("taskId") String taskId,
                                                   @Param("ownerId") String ownerId,
                                                   @Param("editTaskId") String editTaskId);

    @Update("""
            UPDATE creator_edit_task
            SET status = #{status}, user_note = #{note},
                completed_at = CASE WHEN #{status} = 'COMPLETED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                update_time = CURRENT_TIMESTAMP
            WHERE edit_task_id = #{editTaskId} AND is_deleted = 0
            """)
    int updateEditTaskStatus(@Param("editTaskId") String editTaskId,
                             @Param("status") String status,
                             @Param("note") String note);

    @Update("""
            UPDATE creator_preflight_review
            SET event_sequence = event_sequence + 1, update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND is_deleted = 0
            """)
    int touchReview(@Param("reviewId") String reviewId);

    @Update("""
            UPDATE creator_preflight_review
            SET status = 'COMPLETED', current_step = 'DONE', progress_percent = 100,
                executive_summary = #{summary}, actual_cost_usd = #{actualCostUsd},
                lease_owner = NULL, lease_expires_at = NULL,
                next_run_at = NULL, error_code = NULL, error_message = NULL,
                completed_at = CURRENT_TIMESTAMP, event_sequence = event_sequence + 1,
                update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND status = 'RUNNING' AND lease_owner = #{leaseOwner}
            """)
    int completeReview(@Param("reviewId") String reviewId,
                       @Param("leaseOwner") String leaseOwner,
                       @Param("summary") String summary,
                       @Param("actualCostUsd") BigDecimal actualCostUsd);

    @Update("""
            UPDATE creator_preflight_review
            SET status = 'FAILED', error_code = #{errorCode}, error_message = #{errorMessage},
                lease_owner = NULL, lease_expires_at = NULL, next_run_at = NULL,
                event_sequence = event_sequence + 1, update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND status IN ('RUNNING', 'QUEUED', 'RETRY_WAIT')
              AND (lease_owner = #{leaseOwner} OR lease_owner IS NULL) AND is_deleted = 0
            """)
    int failReview(@Param("reviewId") String reviewId,
                   @Param("leaseOwner") String leaseOwner,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE creator_preflight_review
            SET cancel_requested = 1,
                current_step = CASE WHEN status IN ('QUEUED', 'RETRY_WAIT') THEN 'DONE' ELSE current_step END,
                completed_at = CASE WHEN status IN ('QUEUED', 'RETRY_WAIT') THEN CURRENT_TIMESTAMP ELSE completed_at END,
                status = CASE WHEN status IN ('QUEUED', 'RETRY_WAIT') THEN 'CANCELLED' ELSE 'CANCEL_REQUESTED' END,
                event_sequence = event_sequence + 1, update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND task_id = #{taskId} AND owner_id = #{ownerId}
              AND status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT') AND is_deleted = 0
            """)
    int requestCancel(@Param("taskId") String taskId,
                      @Param("ownerId") String ownerId,
                      @Param("reviewId") String reviewId);

    @Update("""
            UPDATE creator_preflight_review
            SET status = 'CANCELLED', current_step = 'DONE', cancel_requested = 1,
                lease_owner = NULL, lease_expires_at = NULL, next_run_at = NULL,
                completed_at = CURRENT_TIMESTAMP, event_sequence = event_sequence + 1,
                update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND status = 'CANCEL_REQUESTED' AND lease_owner = #{leaseOwner}
            """)
    int finishCancellation(@Param("reviewId") String reviewId,
                           @Param("leaseOwner") String leaseOwner);

    @Update("""
            UPDATE creator_preflight_review
            SET status = 'QUEUED', current_step = CASE
                    WHEN EXISTS (SELECT 1 FROM creator_preflight_step s
                                WHERE s.review_id = creator_preflight_review.review_id
                                  AND s.step_type = 'REVIEW_SEGMENTS' AND s.status IN ('SUCCEEDED', 'SKIPPED'))
                    THEN 'SCREEN_AUDIENCE'
                    WHEN EXISTS (SELECT 1 FROM creator_preflight_step s
                                WHERE s.review_id = creator_preflight_review.review_id
                                  AND s.step_type = 'ANALYZE_VIDEO' AND s.status = 'SUCCEEDED')
                    THEN 'REVIEW_SEGMENTS'
                    WHEN EXISTS (SELECT 1 FROM creator_preflight_step s
                                WHERE s.review_id = creator_preflight_review.review_id
                                  AND s.step_type = 'BUILD_TIMELINE' AND s.status = 'SUCCEEDED')
                    THEN 'ANALYZE_VIDEO'
                    WHEN EXISTS (SELECT 1 FROM creator_preflight_step s
                                WHERE s.review_id = creator_preflight_review.review_id
                                  AND s.step_type = 'TRANSCRIBE' AND s.status IN ('SUCCEEDED', 'SKIPPED'))
                    THEN 'BUILD_TIMELINE' ELSE 'TRANSCRIBE' END,
                progress_percent = CASE
                    WHEN EXISTS (SELECT 1 FROM creator_preflight_step s
                                WHERE s.review_id = creator_preflight_review.review_id
                                  AND s.step_type = 'REVIEW_SEGMENTS' AND s.status IN ('SUCCEEDED', 'SKIPPED'))
                    THEN 90
                    WHEN EXISTS (SELECT 1 FROM creator_preflight_step s
                                WHERE s.review_id = creator_preflight_review.review_id
                                  AND s.step_type = 'ANALYZE_VIDEO' AND s.status = 'SUCCEEDED')
                    THEN 82
                    WHEN EXISTS (SELECT 1 FROM creator_preflight_step s
                                WHERE s.review_id = creator_preflight_review.review_id
                                  AND s.step_type = 'BUILD_TIMELINE' AND s.status = 'SUCCEEDED')
                    THEN 75
                    WHEN EXISTS (SELECT 1 FROM creator_preflight_step s
                                WHERE s.review_id = creator_preflight_review.review_id
                                  AND s.step_type = 'TRANSCRIBE' AND s.status IN ('SUCCEEDED', 'SKIPPED'))
                    THEN 65 ELSE 0 END,
                cancel_requested = 0, attempt_count = 0, next_run_at = CURRENT_TIMESTAMP,
                lease_owner = NULL, lease_expires_at = NULL, error_code = NULL, error_message = NULL,
                completed_at = NULL, event_sequence = event_sequence + 1, update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND task_id = #{taskId} AND owner_id = #{ownerId}
              AND status = 'FAILED' AND is_deleted = 0
            """)
    int retryReview(@Param("taskId") String taskId,
                    @Param("ownerId") String ownerId,
                    @Param("reviewId") String reviewId);

    @Update("""
            UPDATE creator_preflight_review
            SET status = 'QUEUED', current_step = 'REVIEW_SEGMENTS', progress_percent = 82,
                cancel_requested = 0, attempt_count = 0, next_run_at = CURRENT_TIMESTAMP,
                lease_owner = NULL, lease_expires_at = NULL, error_code = NULL, error_message = NULL,
                completed_at = NULL, event_sequence = event_sequence + 1, update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND task_id = #{taskId} AND owner_id = #{ownerId}
              AND status = 'COMPLETED' AND is_deleted = 0
            """)
    int queueScreeningCompletion(@Param("taskId") String taskId,
                                 @Param("ownerId") String ownerId,
                                 @Param("reviewId") String reviewId);

    @Update("""
            UPDATE creator_preflight_step
            SET status = 'PENDING',
                provider_task_id = CASE
                    WHEN step_type = 'TRANSCRIBE'
                         AND provider_task_id IS NOT NULL
                         AND error_code <> 'ASR_PROVIDER_FAILED'
                    THEN provider_task_id
                    ELSE NULL
                END,
                output_ref = CASE
                    WHEN step_type = 'TRANSCRIBE'
                         AND provider_task_id IS NOT NULL
                         AND error_code <> 'ASR_PROVIDER_FAILED'
                    THEN output_ref
                    ELSE NULL
                END,
                error_code = NULL, error_message = NULL, started_at = NULL, completed_at = NULL,
                update_time = CURRENT_TIMESTAMP
            WHERE review_id = #{reviewId} AND status = 'FAILED'
            """)
    int resetFailedSteps(@Param("reviewId") String reviewId);

    @Update("""
            UPDATE creator_draft_video
            SET current_review_id = #{reviewId}, update_time = CURRENT_TIMESTAMP
            WHERE version_id = #{versionId} AND task_id = #{taskId} AND owner_id = #{ownerId}
              AND is_deleted = 0
            """)
    int attachReviewToDraft(@Param("taskId") String taskId,
                            @Param("ownerId") String ownerId,
                            @Param("versionId") String versionId,
                            @Param("reviewId") String reviewId);
}
