package com.link.linkagent.creator.media.processing.mapper;

import com.link.linkagent.creator.media.processing.model.MediaProcessingAssetRecord;
import com.link.linkagent.creator.media.processing.model.MediaProcessingJobRecord;
import com.link.linkagent.creator.media.processing.model.MediaProcessingStepRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * P0-2 媒体预处理数据访问层。
 * 任务和素材查询始终携带 taskId、ownerId、versionId，避免只凭资源 ID 读取私有媒体。
 */
@Mapper
public interface MediaProcessingMapper {

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

    @Update("""
            UPDATE creator_draft_video
            SET current_review_id = NULL, update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND version_id = #{versionId}
              AND is_deleted = 0
            """)
    int clearCurrentReview(@Param("taskId") String taskId,
                           @Param("ownerId") String ownerId,
                           @Param("versionId") String versionId);

    @Insert("""
            INSERT INTO creator_media_processing_job (
                job_id, version_id, task_id, owner_id, frame_interval_seconds,
                target_resolution, target_height, model_plan, include_asr, pricing_version,
                estimated_frame_count, estimated_visual_input_tokens, estimated_visual_output_tokens,
                estimated_asr_seconds, estimated_visual_cost_usd, estimated_asr_cost_usd,
                estimated_total_cost_usd, status, current_step, progress_percent, attempt_count
            ) VALUES (
                #{jobId}, #{versionId}, #{taskId}, #{ownerId}, #{frameIntervalSeconds},
                #{targetResolution}, #{targetHeight}, #{modelPlan}, #{includeAsr}, #{pricingVersion},
                #{estimatedFrameCount}, #{estimatedVisualInputTokens}, #{estimatedVisualOutputTokens},
                #{estimatedAsrSeconds}, #{estimatedVisualCostUsd}, #{estimatedAsrCostUsd},
                #{estimatedTotalCostUsd}, #{status}, #{currentStep}, #{progressPercent}, #{attemptCount}
            )
            """)
    int insertJob(MediaProcessingJobRecord record);

    @Insert("""
            INSERT INTO creator_media_processing_step (
                step_id, job_id, step_code, step_name, sequence_no, status, progress_percent
            ) VALUES (
                #{stepId}, #{jobId}, #{stepCode}, #{stepName}, #{sequenceNo}, #{status}, #{progressPercent}
            )
            """)
    int insertStep(MediaProcessingStepRecord record);

    @Select("""
            SELECT id, job_id, version_id, task_id, owner_id, frame_interval_seconds,
                   target_resolution, target_height, model_plan, include_asr, pricing_version,
                   estimated_frame_count, estimated_visual_input_tokens, estimated_visual_output_tokens,
                   estimated_asr_seconds, estimated_visual_cost_usd, estimated_asr_cost_usd,
                   estimated_total_cost_usd, status, current_step, progress_percent, attempt_count,
                   lease_owner, lease_expires_at, signal_summary_json, failure_message,
                   started_at, completed_at, create_time, update_time
            FROM creator_media_processing_job
            WHERE task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND version_id = #{versionId}
              AND is_deleted = 0
            ORDER BY id DESC
            LIMIT 1
            """)
    Optional<MediaProcessingJobRecord> findCurrentJob(@Param("taskId") String taskId,
                                                      @Param("ownerId") String ownerId,
                                                      @Param("versionId") String versionId);

    @Select("""
            SELECT id, job_id, version_id, task_id, owner_id, frame_interval_seconds,
                   target_resolution, target_height, model_plan, include_asr, pricing_version,
                   estimated_frame_count, estimated_visual_input_tokens, estimated_visual_output_tokens,
                   estimated_asr_seconds, estimated_visual_cost_usd, estimated_asr_cost_usd,
                   estimated_total_cost_usd, status, current_step, progress_percent, attempt_count,
                   lease_owner, lease_expires_at, signal_summary_json, failure_message,
                   started_at, completed_at, create_time, update_time
            FROM creator_media_processing_job
            WHERE job_id = #{jobId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND version_id = #{versionId}
              AND is_deleted = 0
            LIMIT 1
            """)
    Optional<MediaProcessingJobRecord> findJob(@Param("taskId") String taskId,
                                               @Param("ownerId") String ownerId,
                                               @Param("versionId") String versionId,
                                               @Param("jobId") String jobId);

    /**
     * Worker 只能使用已由租约 CAS 领取的 jobId 回读任务，不接受页面传入的归属条件。
     */
    @Select("""
            SELECT id, job_id, version_id, task_id, owner_id, frame_interval_seconds,
                   target_resolution, target_height, model_plan, include_asr, pricing_version,
                   estimated_frame_count, estimated_visual_input_tokens, estimated_visual_output_tokens,
                   estimated_asr_seconds, estimated_visual_cost_usd, estimated_asr_cost_usd,
                   estimated_total_cost_usd, status, current_step, progress_percent, attempt_count,
                   lease_owner, lease_expires_at, signal_summary_json, failure_message,
                   started_at, completed_at, create_time, update_time
            FROM creator_media_processing_job
            WHERE job_id = #{jobId}
              AND status = 'RUNNING'
              AND lease_owner = #{leaseOwner}
              AND is_deleted = 0
            LIMIT 1
            """)
    Optional<MediaProcessingJobRecord> findJobForWorker(@Param("jobId") String jobId,
                                                        @Param("leaseOwner") String leaseOwner);

    @Select("""
            SELECT id, job_id, version_id, task_id, owner_id, frame_interval_seconds,
                   target_resolution, target_height, model_plan, include_asr, pricing_version,
                   estimated_frame_count, estimated_visual_input_tokens, estimated_visual_output_tokens,
                   estimated_asr_seconds, estimated_visual_cost_usd, estimated_asr_cost_usd,
                   estimated_total_cost_usd, status, current_step, progress_percent, attempt_count,
                   lease_owner, lease_expires_at, signal_summary_json, failure_message,
                   started_at, completed_at, create_time, update_time
            FROM creator_media_processing_job
            WHERE status = 'QUEUED'
              AND is_deleted = 0
            ORDER BY create_time ASC, id ASC
            LIMIT 1
            """)
    Optional<MediaProcessingJobRecord> findNextQueuedJob();

    @Select("""
            SELECT id, job_id, step_id, step_code, step_name, sequence_no, status,
                   progress_percent, output_summary, failure_message,
                   started_at, completed_at, create_time, update_time
            FROM creator_media_processing_step
            WHERE job_id = #{jobId}
            ORDER BY sequence_no ASC, id ASC
            """)
    List<MediaProcessingStepRecord> listSteps(@Param("jobId") String jobId);

    @Select("""
            SELECT id, asset_id, job_id, version_id, asset_type, bucket_name, object_key,
                   content_type, file_size, sequence_no, timestamp_ms, width, height,
                   duration_ms, create_time, update_time
            FROM creator_media_processing_asset
            WHERE job_id = #{jobId}
              AND is_deleted = 0
            ORDER BY CASE asset_type
                       WHEN 'PREVIEW_VIDEO' THEN 1
                       WHEN 'AUDIO' THEN 2
                       ELSE 3
                     END,
                     sequence_no ASC,
                     id ASC
            """)
    List<MediaProcessingAssetRecord> listAssets(@Param("jobId") String jobId);

    /** 查询版本下全部派生媒体，主动删除时不能只清理最新一次处理结果。 */
    @Select("""
            SELECT id, asset_id, job_id, version_id, asset_type, bucket_name, object_key,
                   content_type, file_size, sequence_no, timestamp_ms, width, height,
                   duration_ms, create_time, update_time
            FROM creator_media_processing_asset
            WHERE version_id = #{versionId}
              AND is_deleted = 0
            ORDER BY id ASC
            """)
    List<MediaProcessingAssetRecord> listAssetsByVersion(@Param("versionId") String versionId);

    /** OSS 对象确认删除后再隐藏素材，历史处理任务和信号摘要继续保留。 */
    @Update("""
            UPDATE creator_media_processing_asset
            SET is_deleted = 1,
                update_time = CURRENT_TIMESTAMP
            WHERE version_id = #{versionId}
              AND is_deleted = 0
            """)
    int markAssetsDeleted(@Param("versionId") String versionId);

    @Select("""
            SELECT a.id, a.asset_id, a.job_id, a.version_id, a.asset_type, a.bucket_name,
                   a.object_key, a.content_type, a.file_size, a.sequence_no, a.timestamp_ms,
                   a.width, a.height, a.duration_ms, a.create_time, a.update_time
            FROM creator_media_processing_asset a
            INNER JOIN creator_media_processing_job j ON j.job_id = a.job_id
            WHERE a.asset_id = #{assetId}
              AND a.version_id = #{versionId}
              AND j.task_id = #{taskId}
              AND j.owner_id = #{ownerId}
              AND a.is_deleted = 0
              AND j.is_deleted = 0
            LIMIT 1
            """)
    Optional<MediaProcessingAssetRecord> findAsset(@Param("taskId") String taskId,
                                                   @Param("ownerId") String ownerId,
                                                   @Param("versionId") String versionId,
                                                   @Param("assetId") String assetId);

    @Update("""
            UPDATE creator_media_processing_job
            SET status = 'RUNNING',
                current_step = 'DOWNLOAD',
                progress_percent = GREATEST(progress_percent, 1),
                attempt_count = attempt_count + 1,
                lease_owner = #{leaseOwner},
                lease_expires_at = #{leaseExpiresAt},
                started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                failure_message = NULL,
                update_time = CURRENT_TIMESTAMP
            WHERE job_id = #{jobId}
              AND status = 'QUEUED'
              AND attempt_count < #{maxAttempts}
              AND is_deleted = 0
            """)
    int claimJob(@Param("jobId") String jobId,
                 @Param("leaseOwner") String leaseOwner,
                 @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
                 @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE creator_media_processing_job
            SET lease_expires_at = #{leaseExpiresAt},
                update_time = CURRENT_TIMESTAMP
            WHERE job_id = #{jobId}
              AND status = 'RUNNING'
              AND lease_owner = #{leaseOwner}
              AND is_deleted = 0
            """)
    int renewLease(@Param("jobId") String jobId,
                   @Param("leaseOwner") String leaseOwner,
                   @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Update("""
            UPDATE creator_media_processing_job
            SET status = 'QUEUED',
                lease_owner = NULL,
                lease_expires_at = NULL,
                failure_message = '处理进程中断，任务已自动重新排队',
                update_time = CURRENT_TIMESTAMP
            WHERE status = 'RUNNING'
              AND lease_expires_at < CURRENT_TIMESTAMP
              AND attempt_count < #{maxAttempts}
              AND is_deleted = 0
            """)
    int requeueExpiredJobs(@Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE creator_media_processing_job
            SET status = 'FAILED',
                lease_owner = NULL,
                lease_expires_at = NULL,
                failure_message = '处理进程多次中断，请手动重试',
                update_time = CURRENT_TIMESTAMP
            WHERE status = 'RUNNING'
              AND lease_expires_at < CURRENT_TIMESTAMP
              AND attempt_count >= #{maxAttempts}
              AND is_deleted = 0
            """)
    int failExhaustedExpiredJobs(@Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE creator_media_processing_job
            SET current_step = #{stepCode},
                progress_percent = #{progressPercent},
                lease_expires_at = #{leaseExpiresAt},
                update_time = CURRENT_TIMESTAMP
            WHERE job_id = #{jobId}
              AND status = 'RUNNING'
              AND lease_owner = #{leaseOwner}
              AND is_deleted = 0
            """)
    int updateJobProgress(@Param("jobId") String jobId,
                          @Param("leaseOwner") String leaseOwner,
                          @Param("stepCode") String stepCode,
                          @Param("progressPercent") int progressPercent,
                          @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Update("""
            UPDATE creator_media_processing_step
            SET status = #{status},
                progress_percent = #{progressPercent},
                output_summary = #{outputSummary},
                failure_message = #{failureMessage},
                started_at = CASE
                    WHEN #{status} = 'RUNNING' THEN COALESCE(started_at, CURRENT_TIMESTAMP)
                    ELSE started_at
                END,
                completed_at = CASE
                    WHEN #{status} IN ('COMPLETED', 'SKIPPED', 'FAILED') THEN CURRENT_TIMESTAMP
                    ELSE completed_at
                END,
                update_time = CURRENT_TIMESTAMP
            WHERE job_id = #{jobId}
              AND step_code = #{stepCode}
            """)
    int updateStep(@Param("jobId") String jobId,
                   @Param("stepCode") String stepCode,
                   @Param("status") String status,
                   @Param("progressPercent") int progressPercent,
                   @Param("outputSummary") String outputSummary,
                   @Param("failureMessage") String failureMessage);

    /**
     * 任务被重新领取时从第一步完整重跑，因为本地临时文件已在上次执行结束时清理。
     */
    @Update("""
            UPDATE creator_media_processing_step
            SET status = 'PENDING',
                progress_percent = 0,
                output_summary = NULL,
                failure_message = NULL,
                started_at = NULL,
                completed_at = NULL,
                update_time = CURRENT_TIMESTAMP
            WHERE job_id = #{jobId}
            """)
    int resetSteps(@Param("jobId") String jobId);

    @Delete("""
            DELETE FROM creator_media_processing_asset
            WHERE job_id = #{jobId}
            """)
    int deleteAssets(@Param("jobId") String jobId);

    @Insert("""
            INSERT INTO creator_media_processing_asset (
                asset_id, job_id, version_id, asset_type, bucket_name, object_key,
                content_type, file_size, sequence_no, timestamp_ms, width, height, duration_ms
            ) VALUES (
                #{assetId}, #{jobId}, #{versionId}, #{assetType}, #{bucketName}, #{objectKey},
                #{contentType}, #{fileSize}, #{sequenceNo}, #{timestampMs}, #{width}, #{height}, #{durationMs}
            )
            """)
    int insertAsset(MediaProcessingAssetRecord record);

    @Update("""
            UPDATE creator_media_processing_job
            SET status = 'COMPLETED',
                current_step = 'DONE',
                progress_percent = 100,
                signal_summary_json = #{signalSummaryJson},
                failure_message = NULL,
                lease_owner = NULL,
                lease_expires_at = NULL,
                completed_at = CURRENT_TIMESTAMP,
                update_time = CURRENT_TIMESTAMP
            WHERE job_id = #{jobId}
              AND status = 'RUNNING'
              AND lease_owner = #{leaseOwner}
              AND is_deleted = 0
            """)
    int completeJob(@Param("jobId") String jobId,
                    @Param("leaseOwner") String leaseOwner,
                    @Param("signalSummaryJson") String signalSummaryJson);

    @Update("""
            UPDATE creator_media_processing_job
            SET status = 'FAILED',
                failure_message = #{failureMessage},
                lease_owner = NULL,
                lease_expires_at = NULL,
                update_time = CURRENT_TIMESTAMP
            WHERE job_id = #{jobId}
              AND status = 'RUNNING'
              AND lease_owner = #{leaseOwner}
              AND is_deleted = 0
            """)
    int failJob(@Param("jobId") String jobId,
                @Param("leaseOwner") String leaseOwner,
                @Param("failureMessage") String failureMessage);

    @Update("""
            UPDATE creator_media_processing_job
            SET status = 'QUEUED',
                current_step = 'QUEUED',
                progress_percent = 0,
                attempt_count = 0,
                lease_owner = NULL,
                lease_expires_at = NULL,
                failure_message = NULL,
                completed_at = NULL,
                update_time = CURRENT_TIMESTAMP
            WHERE job_id = #{jobId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND version_id = #{versionId}
              AND status = 'FAILED'
              AND is_deleted = 0
            """)
    int retryJob(@Param("taskId") String taskId,
                 @Param("ownerId") String ownerId,
                 @Param("versionId") String versionId,
                 @Param("jobId") String jobId);
}
