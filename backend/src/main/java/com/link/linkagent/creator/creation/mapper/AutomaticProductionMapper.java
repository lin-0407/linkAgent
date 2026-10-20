package com.link.linkagent.creator.creation.mapper;

import com.link.linkagent.creator.creation.model.AutomaticStepEvidence;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Optional;

@Mapper
public interface AutomaticProductionMapper {
    @Select("""
            SELECT JSON_OBJECT('source','SYSTEM_AUTOMATIC','publishingSessionId',s.session_id,
                'publishingResultId',g.suggestion_id,'audience',g.audience_profile,'coreMessage',g.content_summary,
                'publishingPlan',JSON_OBJECT('titles',g.title_suggestions,'description',g.description_suggestion))
            FROM creator_task t JOIN creator_workflow_session s ON s.task_id=t.task_id AND s.user_id=t.user_id
                AND s.session_id=#{sessionId} AND s.stage='PRE_PUBLISH' AND s.status='CONFIRMED'
                AND s.confirmed_result_id=#{resultId} AND s.is_deleted=0
            JOIN creator_suggestion g ON g.task_id=t.task_id AND g.session_id=s.session_id
                AND g.suggestion_id=#{resultId} AND g.is_deleted=0
            WHERE t.task_id=#{taskId} AND t.user_id=#{ownerId} AND t.is_deleted=0 AND t.planning_skipped=0
                AND s.id=(SELECT MAX(x.id) FROM creator_workflow_session x WHERE x.task_id=t.task_id
                    AND x.user_id=t.user_id AND x.stage='PRE_PUBLISH' AND x.is_deleted=0)
                AND g.id=(SELECT MAX(x.id) FROM creator_suggestion x WHERE x.task_id=t.task_id AND x.is_deleted=0)
            """)
    Optional<String> confirmedPublishing(@Param("taskId") String taskId, @Param("ownerId") String ownerId,
            @Param("sessionId") String sessionId, @Param("resultId") String resultId);

    @Insert("""
            INSERT INTO creator_creation_auto_step (run_id,task_id,owner_id,production_plan_id,step_code,step_id)
            VALUES (#{runId},#{taskId},#{ownerId},#{productionPlanId},#{stepCode},#{stepId})
            """)
    int insert(AutomaticStepEvidence value);

    @Select("""
            SELECT e.run_id,e.task_id,e.owner_id,e.production_plan_id,e.step_code,e.step_id,
                e.evidence_json,e.lease_generation,e.attempt_id,s.status AS step_status
            FROM creator_creation_auto_step e JOIN creator_production_step s ON s.step_id=e.step_id
                AND s.task_id=e.task_id AND s.plan_id=e.production_plan_id AND s.is_deleted=0
            WHERE e.task_id=#{taskId} AND e.owner_id=#{ownerId} AND e.run_id=#{runId} ORDER BY s.sequence_no
            """)
    List<AutomaticStepEvidence> steps(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId);

    @Update("""
            UPDATE creator_creation_auto_step SET evidence_json=#{evidenceJson},lease_generation=#{leaseGeneration},attempt_id=#{attemptId}
            WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId}
                AND production_plan_id=#{productionPlanId} AND step_code=#{stepCode} AND step_id=#{stepId}
            """)
    int evidence(AutomaticStepEvidence value);

    @Update("""
            UPDATE creator_production_step s JOIN creator_creation_auto_step e
                ON e.step_id=s.step_id AND e.task_id=s.task_id AND e.production_plan_id=s.plan_id
            SET s.status='COMPLETED',s.skip_reason=NULL,s.row_version=s.row_version+1
            WHERE e.task_id=#{taskId} AND e.owner_id=#{ownerId} AND e.run_id=#{runId} AND e.step_code=#{stepCode}
                AND s.is_deleted=0 AND s.status IN ('PENDING','IN_PROGRESS','COMPLETED')
            """)
    int complete(@Param("taskId") String taskId, @Param("ownerId") String ownerId,
            @Param("runId") String runId, @Param("stepCode") String stepCode);
}
