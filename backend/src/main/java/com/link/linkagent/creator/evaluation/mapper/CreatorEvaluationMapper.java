package com.link.linkagent.creator.evaluation.mapper;

import com.link.linkagent.creator.evaluation.model.CreatorEvalCaseRecord;
import com.link.linkagent.creator.evaluation.model.CreatorEvalResultRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * 评测集 MySQL 访问层。
 * 这一层只负责把样例和结果稳定落库，避免把评测逻辑和业务分析逻辑混在一起。
 */
@Mapper
public interface CreatorEvaluationMapper {

    @Insert("""
            INSERT INTO creator_eval_case (
                case_id,
                user_id,
                case_name,
                target_stage,
                task_id,
                input_snapshot,
                expected_points,
                scoring_rubric,
                status
            )
            VALUES (
                #{caseId},
                #{userId},
                #{caseName},
                #{targetStage},
                #{taskId},
                #{inputSnapshot},
                #{expectedPoints},
                #{scoringRubric},
                #{status}
            )
            """)
    int insertCase(CreatorEvalCaseRecord record);

    @Select("""
            SELECT id,
                   case_id,
                   user_id,
                   case_name,
                   target_stage,
                   task_id,
                   input_snapshot,
                   expected_points,
                   scoring_rubric,
                   status,
                   create_time,
                   update_time
            FROM creator_eval_case
            WHERE case_id = #{caseId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "CreatorEvalCaseRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "case_id", property = "caseId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "case_name", property = "caseName"),
            @Result(column = "target_stage", property = "targetStage"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "input_snapshot", property = "inputSnapshot"),
            @Result(column = "expected_points", property = "expectedPoints"),
            @Result(column = "scoring_rubric", property = "scoringRubric"),
            @Result(column = "status", property = "status"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<CreatorEvalCaseRecord> findCaseByCaseId(@Param("caseId") String caseId);

    @Select("""
            <script>
            SELECT id,
                   case_id,
                   user_id,
                   case_name,
                   target_stage,
                   task_id,
                   input_snapshot,
                   expected_points,
                   scoring_rubric,
                   status,
                   create_time,
                   update_time
            FROM creator_eval_case
            WHERE is_deleted = 0
            <if test="userId != null and userId != ''">
                AND user_id = #{userId}
            </if>
            <if test="targetStage != null and targetStage != ''">
                AND target_stage = #{targetStage}
            </if>
            ORDER BY update_time DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    @ResultMap("CreatorEvalCaseRecordMap")
    List<CreatorEvalCaseRecord> listCases(@Param("userId") String userId,
                                          @Param("targetStage") String targetStage,
                                          @Param("limit") int limit);

    @Insert("""
            INSERT INTO creator_eval_result (
                result_id,
                case_id,
                task_id,
                workflow_session_id,
                target_stage,
                model_name,
                output_summary,
                raw_output,
                run_status,
                parse_status,
                elapsed_ms,
                prompt_tokens,
                completion_tokens,
                total_tokens,
                failure_reason,
                readability_score,
                relevance_score,
                completeness_score,
                accuracy_score,
                stability_score,
                cost_score,
                explainability_score,
                reviewer_note
            )
            VALUES (
                #{resultId},
                #{caseId},
                #{taskId},
                #{workflowSessionId},
                #{targetStage},
                #{modelName},
                #{outputSummary},
                #{rawOutput},
                #{runStatus},
                #{parseStatus},
                #{elapsedMs},
                #{promptTokens},
                #{completionTokens},
                #{totalTokens},
                #{failureReason},
                #{readabilityScore},
                #{relevanceScore},
                #{completenessScore},
                #{accuracyScore},
                #{stabilityScore},
                #{costScore},
                #{explainabilityScore},
                #{reviewerNote}
            )
            """)
    int insertResult(CreatorEvalResultRecord record);

    @Select("""
            SELECT id,
                   result_id,
                   case_id,
                   task_id,
                   workflow_session_id,
                   target_stage,
                   model_name,
                   output_summary,
                   raw_output,
                   run_status,
                   parse_status,
                   elapsed_ms,
                   prompt_tokens,
                   completion_tokens,
                   total_tokens,
                   failure_reason,
                   readability_score,
                   relevance_score,
                   completeness_score,
                   accuracy_score,
                   stability_score,
                   cost_score,
                   explainability_score,
                   reviewer_note,
                   create_time,
                   update_time
            FROM creator_eval_result
            WHERE result_id = #{resultId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "CreatorEvalResultRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "result_id", property = "resultId"),
            @Result(column = "case_id", property = "caseId"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "workflow_session_id", property = "workflowSessionId"),
            @Result(column = "target_stage", property = "targetStage"),
            @Result(column = "model_name", property = "modelName"),
            @Result(column = "output_summary", property = "outputSummary"),
            @Result(column = "raw_output", property = "rawOutput"),
            @Result(column = "run_status", property = "runStatus"),
            @Result(column = "parse_status", property = "parseStatus"),
            @Result(column = "elapsed_ms", property = "elapsedMs"),
            @Result(column = "prompt_tokens", property = "promptTokens"),
            @Result(column = "completion_tokens", property = "completionTokens"),
            @Result(column = "total_tokens", property = "totalTokens"),
            @Result(column = "failure_reason", property = "failureReason"),
            @Result(column = "readability_score", property = "readabilityScore"),
            @Result(column = "relevance_score", property = "relevanceScore"),
            @Result(column = "completeness_score", property = "completenessScore"),
            @Result(column = "accuracy_score", property = "accuracyScore"),
            @Result(column = "stability_score", property = "stabilityScore"),
            @Result(column = "cost_score", property = "costScore"),
            @Result(column = "explainability_score", property = "explainabilityScore"),
            @Result(column = "reviewer_note", property = "reviewerNote"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<CreatorEvalResultRecord> findResultByResultId(@Param("resultId") String resultId);

    @Select("""
            <script>
            SELECT id,
                   result_id,
                   case_id,
                   task_id,
                   workflow_session_id,
                   target_stage,
                   model_name,
                   output_summary,
                   raw_output,
                   run_status,
                   parse_status,
                   elapsed_ms,
                   prompt_tokens,
                   completion_tokens,
                   total_tokens,
                   failure_reason,
                   readability_score,
                   relevance_score,
                   completeness_score,
                   accuracy_score,
                   stability_score,
                   cost_score,
                   explainability_score,
                   reviewer_note,
                   create_time,
                   update_time
            FROM creator_eval_result
            WHERE case_id = #{caseId}
              AND is_deleted = 0
            ORDER BY update_time DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    @ResultMap("CreatorEvalResultRecordMap")
    List<CreatorEvalResultRecord> listResultsByCaseId(@Param("caseId") String caseId,
                                                      @Param("limit") int limit);

}
