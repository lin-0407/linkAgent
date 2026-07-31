package com.link.linkagent.llm.usage;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型 API 开销统计 MySQL 访问层。
 * 聚合逻辑放在 SQL 层，是因为数据库更擅长做分组求和，前端只负责展示结果。
 */
@Mapper
public interface LlmApiUsageMapper {

    @Insert("""
            INSERT INTO llm_api_call_log (
                call_id,
                task_id,
                trace_id,
                request_id,
                workflow_session_id,
                workflow_step_id,
                workflow_step_name,
                workflow_stage,
                model_category,
                scene,
                model_name,
                reasoning_effort,
                prompt_tokens,
                completion_tokens,
                total_tokens,
                elapsed_ms,
                status,
                error_message,
                input_count
            )
            VALUES (
                #{callId},
                #{taskId},
                #{traceId},
                #{requestId},
                #{workflowSessionId},
                #{workflowStepId},
                #{workflowStepName},
                #{workflowStage},
                #{modelCategory},
                #{scene},
                #{modelName},
                #{reasoningEffort},
                #{promptTokens},
                #{completionTokens},
                #{totalTokens},
                #{elapsedMs},
                #{status},
                #{errorMessage},
                #{inputCount}
            )
            """)
    int insert(LlmApiCallRecord record);

    @Select("""
            SELECT COALESCE(model_category, 'UNKNOWN') AS model_category,
                   COUNT(*) AS call_count,
                   SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_count,
                   SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed_count,
                   SUM(CASE WHEN status = 'SKIPPED' THEN 1 ELSE 0 END) AS skipped_count,
                   SUM(total_tokens) AS total_tokens,
                   SUM(prompt_tokens) AS prompt_tokens,
                   SUM(completion_tokens) AS completion_tokens,
                   SUM(elapsed_ms) AS total_elapsed_ms,
                   ROUND(AVG(elapsed_ms)) AS average_elapsed_ms
            FROM llm_api_call_log
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            GROUP BY model_category
            ORDER BY FIELD(model_category, 'TEXT', 'EMBEDDING', 'RERANK'), model_category
            """)
    @Results(id = "LlmApiUsageCategorySummaryMap", value = {
            @Result(column = "model_category", property = "modelCategory"),
            @Result(column = "call_count", property = "callCount"),
            @Result(column = "success_count", property = "successCount"),
            @Result(column = "failed_count", property = "failedCount"),
            @Result(column = "skipped_count", property = "skippedCount"),
            @Result(column = "total_tokens", property = "totalTokens"),
            @Result(column = "prompt_tokens", property = "promptTokens"),
            @Result(column = "completion_tokens", property = "completionTokens"),
            @Result(column = "total_elapsed_ms", property = "totalElapsedMs"),
            @Result(column = "average_elapsed_ms", property = "averageElapsedMs")
    })
    List<LlmApiUsageCategorySummary> summarizeByTaskId(@Param("taskId") String taskId);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM llm_api_call_log
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            <if test="modelCategory != null and modelCategory != ''">
                AND model_category = #{modelCategory}
            </if>
            </script>
            """)
    long countCallsByTaskId(@Param("taskId") String taskId,
                            @Param("modelCategory") String modelCategory);

    @Select("""
            <script>
            SELECT id,
                   call_id,
                   task_id,
                   trace_id,
                   request_id,
                   workflow_session_id,
                   workflow_step_id,
                   workflow_step_name,
                   workflow_stage,
                   model_category,
                   scene,
                   model_name,
                   reasoning_effort,
                   prompt_tokens,
                   completion_tokens,
                   total_tokens,
                   elapsed_ms,
                   status,
                   error_message,
                   input_count,
                   create_time
            FROM llm_api_call_log
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            <if test="modelCategory != null and modelCategory != ''">
                AND model_category = #{modelCategory}
            </if>
            ORDER BY create_time DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @Results(id = "LlmApiCallRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "call_id", property = "callId"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "trace_id", property = "traceId"),
            @Result(column = "request_id", property = "requestId"),
            @Result(column = "workflow_session_id", property = "workflowSessionId"),
            @Result(column = "workflow_step_id", property = "workflowStepId"),
            @Result(column = "workflow_step_name", property = "workflowStepName"),
            @Result(column = "workflow_stage", property = "workflowStage"),
            @Result(column = "model_category", property = "modelCategory"),
            @Result(column = "scene", property = "scene"),
            @Result(column = "model_name", property = "modelName"),
            @Result(column = "reasoning_effort", property = "reasoningEffort"),
            @Result(column = "prompt_tokens", property = "promptTokens"),
            @Result(column = "completion_tokens", property = "completionTokens"),
            @Result(column = "total_tokens", property = "totalTokens"),
            @Result(column = "elapsed_ms", property = "elapsedMs"),
            @Result(column = "status", property = "status"),
            @Result(column = "error_message", property = "errorMessage"),
            @Result(column = "input_count", property = "inputCount"),
            @Result(column = "create_time", property = "createTime")
    })
    List<LlmApiCallRecord> listCallsByTaskId(@Param("taskId") String taskId,
                                             @Param("modelCategory") String modelCategory,
                                             @Param("limit") int limit,
                                             @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) AS call_count,
                   SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_count,
                   SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed_count,
                   SUM(CASE WHEN status = 'SKIPPED' THEN 1 ELSE 0 END) AS skipped_count,
                   SUM(total_tokens) AS total_tokens,
                   SUM(prompt_tokens) AS prompt_tokens,
                   SUM(completion_tokens) AS completion_tokens,
                   SUM(elapsed_ms) AS total_elapsed_ms
            FROM llm_api_call_log
            WHERE is_deleted = 0
            <if test="startTime != null">
                AND create_time &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND create_time &lt;= #{endTime}
            </if>
            <if test="modelName != null and modelName != ''">
                AND model_name LIKE CONCAT('%', #{modelName}, '%')
            </if>
            <if test="scene != null and scene != ''">
                AND scene LIKE CONCAT('%', #{scene}, '%')
            </if>
            <if test="modelCategory != null and modelCategory != ''">
                AND model_category = #{modelCategory}
            </if>
            <if test="status != null and status != ''">
                AND status = #{status}
            </if>
            </script>
            """)
    @Results(id = "LlmApiCallLogSummaryMap", value = {
            @Result(column = "call_count", property = "callCount"),
            @Result(column = "success_count", property = "successCount"),
            @Result(column = "failed_count", property = "failedCount"),
            @Result(column = "skipped_count", property = "skippedCount"),
            @Result(column = "total_tokens", property = "totalTokens"),
            @Result(column = "prompt_tokens", property = "promptTokens"),
            @Result(column = "completion_tokens", property = "completionTokens"),
            @Result(column = "total_elapsed_ms", property = "totalElapsedMs")
    })
    LlmApiCallLogSummary summarizeCalls(@Param("startTime") LocalDateTime startTime,
                                        @Param("endTime") LocalDateTime endTime,
                                        @Param("modelName") String modelName,
                                        @Param("scene") String scene,
                                        @Param("modelCategory") String modelCategory,
                                        @Param("status") String status);

    @Select("""
            <script>
            SELECT id,
                   call_id,
                   task_id,
                   trace_id,
                   request_id,
                   workflow_session_id,
                   workflow_step_id,
                   workflow_step_name,
                   workflow_stage,
                   model_category,
                   scene,
                   model_name,
                   reasoning_effort,
                   prompt_tokens,
                   completion_tokens,
                   total_tokens,
                   elapsed_ms,
                   status,
                   error_message,
                   input_count,
                   create_time
            FROM llm_api_call_log
            WHERE is_deleted = 0
            <if test="startTime != null">
                AND create_time &gt;= #{startTime}
            </if>
            <if test="endTime != null">
                AND create_time &lt;= #{endTime}
            </if>
            <if test="modelName != null and modelName != ''">
                AND model_name LIKE CONCAT('%', #{modelName}, '%')
            </if>
            <if test="scene != null and scene != ''">
                AND scene LIKE CONCAT('%', #{scene}, '%')
            </if>
            <if test="modelCategory != null and modelCategory != ''">
                AND model_category = #{modelCategory}
            </if>
            <if test="status != null and status != ''">
                AND status = #{status}
            </if>
            ORDER BY create_time DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @ResultMap("LlmApiCallRecordMap")
    List<LlmApiCallRecord> listCalls(@Param("startTime") LocalDateTime startTime,
                                     @Param("endTime") LocalDateTime endTime,
                                     @Param("modelName") String modelName,
                                     @Param("scene") String scene,
                                     @Param("modelCategory") String modelCategory,
                                     @Param("status") String status,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    @Select("""
            SELECT id,
                   call_id,
                   task_id,
                   trace_id,
                   request_id,
                   workflow_session_id,
                   workflow_step_id,
                   workflow_step_name,
                   workflow_stage,
                   model_category,
                   scene,
                   model_name,
                   reasoning_effort,
                   prompt_tokens,
                   completion_tokens,
                   total_tokens,
                   elapsed_ms,
                   status,
                   error_message,
                   input_count,
                   create_time
            FROM llm_api_call_log
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            ORDER BY create_time DESC, id DESC
            LIMIT #{limit}
            """)
    @ResultMap("LlmApiCallRecordMap")
    List<LlmApiCallRecord> listRecentCallsByTaskId(@Param("taskId") String taskId,
                                                   @Param("limit") int limit);

    @Select("""
            SELECT id,
                   call_id,
                   task_id,
                   trace_id,
                   request_id,
                   workflow_session_id,
                   workflow_step_id,
                   workflow_step_name,
                   workflow_stage,
                   model_category,
                   scene,
                   model_name,
                   reasoning_effort,
                   prompt_tokens,
                   completion_tokens,
                   total_tokens,
                   elapsed_ms,
                   status,
                   error_message,
                   input_count,
                   create_time
            FROM llm_api_call_log
            WHERE task_id = #{taskId}
              AND workflow_session_id = #{workflowSessionId}
              AND is_deleted = 0
            ORDER BY create_time ASC, id ASC
            """)
    @ResultMap("LlmApiCallRecordMap")
    List<LlmApiCallRecord> listCallsByWorkflowSession(@Param("taskId") String taskId,
                                                      @Param("workflowSessionId") String workflowSessionId);
}
