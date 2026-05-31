package com.link.linkagent.creator.workflow.mapper;

import com.link.linkagent.creator.workflow.model.CreatorWorkflowMessageRecord;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowSessionRecord;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStepRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * 创作者工作流 MySQL 访问层。
 * 第一版继续使用注解 SQL，表结构稳定后再考虑 XML，避免过早增加文件和心智负担。
 */
@Mapper
public interface CreatorWorkflowMapper {

    @Insert("""
            INSERT INTO creator_workflow_session (
                session_id,
                task_id,
                stage,
                status,
                user_id,
                confirmed_result_id,
                error_message
            )
            VALUES (
                #{sessionId},
                #{taskId},
                #{stage},
                #{status},
                #{userId},
                #{confirmedResultId},
                #{errorMessage}
            )
            """)
    int insertSession(CreatorWorkflowSessionRecord record);

    @Select("""
            SELECT id,
                   session_id,
                   task_id,
                   stage,
                   status,
                   user_id,
                   confirmed_result_id,
                   error_message,
                   create_time,
                   update_time
            FROM creator_workflow_session
            WHERE task_id = #{taskId}
              AND stage = #{stage}
              AND is_deleted = 0
            ORDER BY update_time DESC, id DESC
            LIMIT 1
            """)
    @Results(id = "CreatorWorkflowSessionRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "stage", property = "stage"),
            @Result(column = "status", property = "status"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "confirmed_result_id", property = "confirmedResultId"),
            @Result(column = "error_message", property = "errorMessage"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<CreatorWorkflowSessionRecord> findLatestSession(@Param("taskId") String taskId,
                                                             @Param("stage") String stage);

    @Select("""
            SELECT id,
                   session_id,
                   task_id,
                   stage,
                   status,
                   user_id,
                   confirmed_result_id,
                   error_message,
                   create_time,
                   update_time
            FROM creator_workflow_session
            WHERE task_id = #{taskId}
              AND session_id = #{sessionId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @ResultMap("CreatorWorkflowSessionRecordMap")
    Optional<CreatorWorkflowSessionRecord> findSession(@Param("taskId") String taskId,
                                                       @Param("sessionId") String sessionId);

    @Update("""
            UPDATE creator_workflow_session
            SET status = #{status},
                error_message = #{errorMessage},
                update_time = CURRENT_TIMESTAMP
            WHERE session_id = #{sessionId}
              AND is_deleted = 0
            """)
    int updateSessionStatus(@Param("sessionId") String sessionId,
                            @Param("status") String status,
                            @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE creator_workflow_session
            SET status = #{status},
                confirmed_result_id = #{confirmedResultId},
                error_message = NULL,
                update_time = CURRENT_TIMESTAMP
            WHERE session_id = #{sessionId}
              AND is_deleted = 0
            """)
    int updateSessionConfirmation(@Param("sessionId") String sessionId,
                                  @Param("status") String status,
                                  @Param("confirmedResultId") String confirmedResultId);

    @Update("""
            UPDATE creator_workflow_session
            SET update_time = CURRENT_TIMESTAMP
            WHERE session_id = #{sessionId}
              AND is_deleted = 0
            """)
    int touchSession(@Param("sessionId") String sessionId);

    @Select("""
            SELECT COALESCE(MAX(sequence_no), 0) + 1
            FROM creator_workflow_message
            WHERE session_id = #{sessionId}
              AND is_deleted = 0
            """)
    int nextMessageSequence(@Param("sessionId") String sessionId);

    @Insert("""
            INSERT INTO creator_workflow_message (
                message_id,
                session_id,
                role,
                content,
                content_type,
                detail_ref_type,
                detail_ref_id,
                sequence_no
            )
            VALUES (
                #{messageId},
                #{sessionId},
                #{role},
                #{content},
                #{contentType},
                #{detailRefType},
                #{detailRefId},
                #{sequenceNo}
            )
            """)
    int insertMessage(CreatorWorkflowMessageRecord record);

    @Select("""
            SELECT id,
                   message_id,
                   session_id,
                   role,
                   content,
                   content_type,
                   detail_ref_type,
                   detail_ref_id,
                   sequence_no,
                   create_time
            FROM creator_workflow_message
            WHERE session_id = #{sessionId}
              AND is_deleted = 0
            ORDER BY sequence_no ASC, id ASC
            """)
    @Results(id = "CreatorWorkflowMessageRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "message_id", property = "messageId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "role", property = "role"),
            @Result(column = "content", property = "content"),
            @Result(column = "content_type", property = "contentType"),
            @Result(column = "detail_ref_type", property = "detailRefType"),
            @Result(column = "detail_ref_id", property = "detailRefId"),
            @Result(column = "sequence_no", property = "sequenceNo"),
            @Result(column = "create_time", property = "createTime")
    })
    List<CreatorWorkflowMessageRecord> listMessages(@Param("sessionId") String sessionId);

    @Select("""
            SELECT id,
                   message_id,
                   session_id,
                   role,
                   content,
                   content_type,
                   detail_ref_type,
                   detail_ref_id,
                   sequence_no,
                   create_time
            FROM creator_workflow_message
            WHERE message_id = #{messageId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @ResultMap("CreatorWorkflowMessageRecordMap")
    Optional<CreatorWorkflowMessageRecord> findMessageByMessageId(@Param("messageId") String messageId);

    @Insert("""
            INSERT INTO creator_workflow_step (
                step_id,
                session_id,
                step_type,
                step_name,
                status,
                input_summary,
                output_summary,
                raw_output,
                error_message,
                start_time
            )
            VALUES (
                #{stepId},
                #{sessionId},
                #{stepType},
                #{stepName},
                #{status},
                #{inputSummary},
                #{outputSummary},
                #{rawOutput},
                #{errorMessage},
                CURRENT_TIMESTAMP
            )
            """)
    int insertStep(CreatorWorkflowStepRecord record);

    @Update("""
            UPDATE creator_workflow_step
            SET status = #{status},
                output_summary = #{outputSummary},
                raw_output = #{rawOutput},
                error_message = NULL,
                end_time = CURRENT_TIMESTAMP
            WHERE step_id = #{stepId}
              AND is_deleted = 0
            """)
    int completeStepSuccess(@Param("stepId") String stepId,
                            @Param("status") String status,
                            @Param("outputSummary") String outputSummary,
                            @Param("rawOutput") String rawOutput);

    @Update("""
            UPDATE creator_workflow_step
            SET status = #{status},
                error_message = #{errorMessage},
                end_time = CURRENT_TIMESTAMP
            WHERE step_id = #{stepId}
              AND is_deleted = 0
            """)
    int completeStepFailure(@Param("stepId") String stepId,
                            @Param("status") String status,
                            @Param("errorMessage") String errorMessage);

    @Select("""
            SELECT id,
                   step_id,
                   session_id,
                   step_type,
                   step_name,
                   status,
                   input_summary,
                   output_summary,
                   raw_output,
                   error_message,
                   start_time,
                   end_time,
                   create_time
            FROM creator_workflow_step
            WHERE session_id = #{sessionId}
              AND is_deleted = 0
            ORDER BY create_time ASC, id ASC
            """)
    @Results(id = "CreatorWorkflowStepRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "step_id", property = "stepId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "step_type", property = "stepType"),
            @Result(column = "step_name", property = "stepName"),
            @Result(column = "status", property = "status"),
            @Result(column = "input_summary", property = "inputSummary"),
            @Result(column = "output_summary", property = "outputSummary"),
            @Result(column = "raw_output", property = "rawOutput"),
            @Result(column = "error_message", property = "errorMessage"),
            @Result(column = "start_time", property = "startTime"),
            @Result(column = "end_time", property = "endTime"),
            @Result(column = "create_time", property = "createTime")
    })
    List<CreatorWorkflowStepRecord> listSteps(@Param("sessionId") String sessionId);
}
