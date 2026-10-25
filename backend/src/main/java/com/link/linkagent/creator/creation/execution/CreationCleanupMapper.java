package com.link.linkagent.creator.creation.execution;

import com.link.linkagent.creator.creation.model.GeneratedDraftAttempt;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface CreationCleanupMapper {
    @Select("""
        SELECT (SELECT COUNT(*) FROM creator_creation_render_attempt a,
            JSON_TABLE(IF(JSON_VALID(a.objects_json),a.objects_json,'[]'),'$[*]' COLUMNS(object_key VARCHAR(1000) PATH '$.objectKey')) o
            WHERE a.status='REGISTERED' AND a.bucket_name=#{bucket} AND o.object_key=#{key})
        + (SELECT COUNT(*) FROM creator_draft_video WHERE bucket_name=#{bucket} AND object_key=#{key})
        + (SELECT COUNT(*) FROM creator_creation_package WHERE status='READY' AND bucket_name=#{bucket} AND object_key=#{key})
        """)
    int references(@Param("bucket")String bucket,@Param("key")String key);
    @Select("""
        SELECT a.run_id,a.attempt_id,a.task_id,a.owner_id,a.lease_generation,a.version_id,a.production_plan_id,
            a.input_hash,a.bundle_hash,a.bucket_name,a.objects_json,a.status
        FROM creator_creation_render_attempt a JOIN creator_creation_run r ON r.run_id=a.run_id AND r.task_id=a.task_id AND r.owner_id=a.owner_id
        WHERE a.status='UPLOADING' AND r.status IN ('COMPLETED','FAILED','CANCELLED')
            AND r.completed_at<TIMESTAMPADD(SECOND,-180,NOW(6)) ORDER BY r.completed_at LIMIT 8
        """)
    List<GeneratedDraftAttempt> orphanedAttempts();
    @Update("UPDATE creator_creation_render_attempt SET status='CLEANED' WHERE run_id=#{runId} AND attempt_id=#{attemptId} AND task_id=#{taskId} AND owner_id=#{ownerId} AND status='UPLOADING'")
    int cleaned(GeneratedDraftAttempt attempt);
    @Select("""
        SELECT r.run_id FROM creator_creation_run r WHERE r.status IN ('COMPLETED','FAILED','CANCELLED')
            AND r.completed_at<TIMESTAMPADD(SECOND,-#{graceSeconds},NOW(6)) AND r.lease_expires_at IS NULL
            AND NOT EXISTS(SELECT 1 FROM creator_creation_cleanup c WHERE c.run_id=r.run_id AND c.local_done=1)
            ORDER BY r.completed_at LIMIT 10
        """)
    List<String> finishedRuns(@Param("graceSeconds")int graceSeconds);
    @Insert("INSERT INTO creator_creation_cleanup(run_id,local_done) VALUES(#{run},1) ON DUPLICATE KEY UPDATE local_done=1")
    int localDone(@Param("run")String run);
}
