package com.link.linkagent.creator.creation.mapper;

import com.link.linkagent.creator.creation.model.GeneratedDraftAttempt;
import com.link.linkagent.creator.creation.model.VideoVersionContextRecord;
import org.apache.ibatis.annotations.*;
import java.util.Optional;

@Mapper
public interface GeneratedDraftMapper {
    String COLUMNS = "run_id,attempt_id,task_id,owner_id,lease_generation,version_id,production_plan_id,input_hash,bundle_hash,bucket_name,objects_json,status";
    @Select("SELECT " + COLUMNS + " FROM creator_creation_render_attempt WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId} AND attempt_id=#{attemptId}")
    Optional<GeneratedDraftAttempt> attempt(@Param("taskId") String taskId, @Param("ownerId") String ownerId,
            @Param("runId") String runId, @Param("attemptId") String attemptId);

    @Select("SELECT " + COLUMNS + " FROM creator_creation_render_attempt WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId} AND status='REGISTERED'")
    Optional<GeneratedDraftAttempt> registered(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId);

    @Insert("""
            INSERT INTO creator_creation_render_attempt (run_id,attempt_id,task_id,owner_id,lease_generation,version_id,
                production_plan_id,input_hash,bundle_hash,bucket_name,objects_json,status)
            VALUES (#{runId},#{attemptId},#{taskId},#{ownerId},#{leaseGeneration},#{versionId},#{productionPlanId},
                #{inputHash},#{bundleHash},#{bucketName},#{objectsJson},'UPLOADING')
            """)
    int insert(GeneratedDraftAttempt attempt);

    @Update("UPDATE creator_creation_render_attempt SET status='REGISTERED' WHERE run_id=#{runId} AND attempt_id=#{attemptId} AND task_id=#{taskId} AND owner_id=#{ownerId} AND lease_generation=#{leaseGeneration} AND status='UPLOADING'")
    int markRegistered(GeneratedDraftAttempt attempt);

    @Insert("""
            INSERT INTO creator_video_version_context (version_id,task_id,owner_id,planning_skipped,
                publishing_session_id,publishing_result_id,production_plan_id,snapshot_json)
            VALUES (#{versionId},#{taskId},#{ownerId},0,#{publishingSessionId},#{publishingResultId},#{productionPlanId},#{snapshotJson})
            """)
    int insertContext(VideoVersionContextRecord context);

    // 接管只能替换读取到的那一次探测，旧进程即使返回也不能覆盖新 token。
    @Update("""
            UPDATE creator_draft_video SET status='PROBING',probe_attempt_id=#{nextToken},
                duration_ms=NULL,width=NULL,height=NULL,frame_rate=NULL,video_codec=NULL,audio_codec=NULL,has_audio=NULL,
                update_time=CURRENT_TIMESTAMP
            WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND version_id=#{versionId}
                AND status=#{expectedStatus} AND probe_attempt_id <=> #{expectedToken}
                AND media_deleted_at IS NULL AND is_deleted=0
            """)
    int claimProbe(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("versionId") String versionId,
            @Param("expectedStatus") String expectedStatus, @Param("expectedToken") String expectedToken, @Param("nextToken") String nextToken);
}
