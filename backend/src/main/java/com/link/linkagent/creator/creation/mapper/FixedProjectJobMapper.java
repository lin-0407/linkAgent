package com.link.linkagent.creator.creation.mapper;

import com.link.linkagent.creator.creation.model.FixedProjectJob;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Optional;

@Mapper
public interface FixedProjectJobMapper {
    @Select("""
            SELECT p.target_duration_ms FROM creator_production_plan p
            JOIN creator_creation_auto_step e ON e.production_plan_id=p.plan_id AND e.task_id=p.task_id
                AND e.owner_id=p.owner_id AND e.step_code='APPROVED'
            WHERE e.task_id=#{taskId} AND e.owner_id=#{ownerId} AND e.run_id=#{runId} AND p.is_deleted=0
            """)
    Optional<Long> automaticTargetDuration(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId);

    @Select("SELECT run_id,task_id,owner_id,source_attempt_id,project_json FROM creator_creation_fixed_job WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId}")
    Optional<FixedProjectJob> find(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId);

    @Insert("INSERT INTO creator_creation_fixed_job (run_id,task_id,owner_id,source_attempt_id,project_json) VALUES (#{runId},#{taskId},#{ownerId},#{sourceAttemptId},#{projectJson})")
    int insert(FixedProjectJob job);

    // 查询只是候选提示，实际领取仍由任务锁、活动指针和数据库租约原子裁决。
    @Select("""
            SELECT j.run_id,j.task_id,j.owner_id,j.source_attempt_id,j.project_json
            FROM creator_creation_fixed_job j
            JOIN creator_creation_run r ON r.run_id=j.run_id AND r.task_id=j.task_id AND r.owner_id=j.owner_id
            JOIN creator_creation_workspace w ON w.task_id=r.task_id AND w.owner_id=r.owner_id
                AND w.active_run_id=r.run_id AND w.is_deleted=0
            JOIN creator_task t ON t.task_id=r.task_id AND t.user_id=r.owner_id AND t.is_deleted=0
            WHERE r.kind='FULL' AND NOT EXISTS (SELECT 1 FROM creator_creation_media_link l WHERE l.run_id=r.run_id)
                AND (r.status='QUEUED' OR
                (r.status IN ('RUNNING','CANCEL_REQUESTED') AND
                    (r.lease_expires_at IS NULL OR r.lease_expires_at<=CURRENT_TIMESTAMP(6))))
            ORDER BY IF(r.status='CANCEL_REQUESTED',0,1),r.id LIMIT 8
            """)
    List<FixedProjectJob> candidates();
}
