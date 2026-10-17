package com.link.linkagent.assistant;

import com.link.linkagent.assistant.AssistantModels.ActionProposal;
import com.link.linkagent.assistant.AssistantModels.AgentTrace;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Optional;

/** 提议和轨迹落库，确保刷新页面仍可查看，重复确认由数据库条件更新拦住。 */
@Mapper
public interface AssistantMapper {
    @Insert("""
            INSERT INTO creator_assistant_action
            (proposal_id,task_id,task_name,action_type,guidance,description,source_fingerprint,status,create_time,expires_at)
            VALUES (#{proposalId},#{taskId},#{taskName},#{actionType},#{guidance},#{description},#{sourceFingerprint},
                    #{status},#{createTime},#{expiresAt})
            """)
    int insertAction(ActionProposal proposal);

    @Select("""
            SELECT proposal_id,task_id,task_name,action_type,guidance,description,source_fingerprint,status,
                   result_message,create_time,expires_at
            FROM creator_assistant_action WHERE proposal_id=#{proposalId}
            """)
    Optional<ActionProposal> findAction(String proposalId);

    @Select("""
            SELECT proposal_id,task_id,task_name,action_type,guidance,description,source_fingerprint,status,
                   result_message,create_time,expires_at
            FROM creator_assistant_action WHERE task_id=#{taskId}
            ORDER BY create_time DESC,proposal_id DESC LIMIT 5
            """)
    List<ActionProposal> listActions(String taskId);

    @Update("""
            UPDATE creator_assistant_action SET status='RUNNING'
            WHERE proposal_id=#{proposalId} AND status='PENDING' AND expires_at>#{now}
            """)
    int claimAction(@Param("proposalId") String proposalId, @Param("now") java.time.LocalDateTime now);

    @Update("""
            UPDATE creator_assistant_action SET status=#{status},result_message=#{message}
            WHERE proposal_id=#{proposalId} AND status='RUNNING'
            """)
    int finishAction(@Param("proposalId") String proposalId, @Param("status") String status,
                     @Param("message") String message);

    @Insert("""
            INSERT INTO creator_agent_execution (run_id,task_id,scene,execution_mode,status,create_time)
            VALUES (#{runId},#{taskId},#{scene},#{executionMode},'RUNNING',CURRENT_TIMESTAMP)
            """)
    int insertTrace(@Param("runId") String runId, @Param("taskId") String taskId,
                    @Param("scene") String scene, @Param("executionMode") String mode);

    @Update("""
            UPDATE creator_agent_execution SET status=#{status},trace_json=#{trace},error_message=#{error}
            WHERE run_id=#{runId} AND status='RUNNING'
            """)
    int finishTrace(@Param("runId") String runId, @Param("status") String status,
                    @Param("trace") String trace, @Param("error") String error);

    @Select("""
            SELECT run_id,task_id,scene,execution_mode,status,trace_json,error_message,create_time
            FROM creator_agent_execution WHERE task_id=#{taskId} ORDER BY create_time DESC,run_id DESC LIMIT 4
            """)
    List<AgentTrace> listTraces(String taskId);
}
