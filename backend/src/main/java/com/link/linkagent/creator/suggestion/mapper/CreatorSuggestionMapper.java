package com.link.linkagent.creator.suggestion.mapper;

import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * 发布前优化建议访问层。
 * 工作流结果按任务和会话分别保存，避免并行会话的完成顺序改变当前页面结果。
 */
@Mapper
public interface CreatorSuggestionMapper {

    @Insert("""
            INSERT INTO creator_suggestion (
                suggestion_id,
                task_id,
                session_id,
                content_summary,
                creator_dilemma,
                audience_profile,
                audience_hook,
                content_positioning,
                selling_points,
                risk_points,
                title_suggestions,
                description_suggestion,
                actionable_revision_plan,
                tag_suggestions,
                partition_suggestion,
                evidence_refs,
                missing_info,
                generation_mode,
                quality_status,
                   audit_report,
                   raw_output,
                parse_status
            )
            VALUES (
                #{suggestionId},
                #{taskId},
                #{sessionId},
                #{contentSummary},
                #{creatorDilemma},
                #{audienceProfile},
                #{audienceHook},
                #{contentPositioning},
                #{sellingPoints},
                #{riskPoints},
                #{titleSuggestions},
                #{descriptionSuggestion},
                #{actionableRevisionPlan},
                #{tagSuggestions},
                #{partitionSuggestion},
                #{evidenceRefs},
                #{missingInfo},
                #{generationMode},
                #{qualityStatus},
                #{auditReport},
                #{rawOutput},
                #{parseStatus}
            )
            ON DUPLICATE KEY UPDATE
                suggestion_id = VALUES(suggestion_id),
                session_id = VALUES(session_id),
                content_summary = VALUES(content_summary),
                creator_dilemma = VALUES(creator_dilemma),
                audience_profile = VALUES(audience_profile),
                audience_hook = VALUES(audience_hook),
                content_positioning = VALUES(content_positioning),
                selling_points = VALUES(selling_points),
                risk_points = VALUES(risk_points),
                title_suggestions = VALUES(title_suggestions),
                description_suggestion = VALUES(description_suggestion),
                actionable_revision_plan = VALUES(actionable_revision_plan),
                tag_suggestions = VALUES(tag_suggestions),
                partition_suggestion = VALUES(partition_suggestion),
                evidence_refs = VALUES(evidence_refs),
                missing_info = VALUES(missing_info),
                generation_mode = VALUES(generation_mode),
                quality_status = VALUES(quality_status),
                audit_report = VALUES(audit_report),
                raw_output = VALUES(raw_output),
                parse_status = VALUES(parse_status),
                is_deleted = 0,
                update_time = CURRENT_TIMESTAMP
            """)
    int upsert(CreatorSuggestionRecord record);

    /**
     * 任务级兼容查询优先返回已确认会话绑定的方案，避免旧会话晚完成后被下游误用。
     */
    @Select("""
            SELECT suggestion.id,
                   suggestion.suggestion_id,
                   suggestion.task_id,
                   suggestion.session_id,
                   suggestion.content_summary,
                   suggestion.creator_dilemma,
                   suggestion.audience_profile,
                   suggestion.audience_hook,
                   suggestion.content_positioning,
                   suggestion.selling_points,
                   suggestion.risk_points,
                   suggestion.title_suggestions,
                   suggestion.description_suggestion,
                   suggestion.actionable_revision_plan,
                   suggestion.tag_suggestions,
                   suggestion.partition_suggestion,
                   suggestion.evidence_refs,
                   suggestion.missing_info,
                   suggestion.generation_mode,
                   suggestion.quality_status,
                   suggestion.audit_report,
                   suggestion.raw_output,
                   suggestion.parse_status,
                   suggestion.create_time,
                   suggestion.update_time
            FROM creator_suggestion suggestion
            LEFT JOIN creator_workflow_session workflow_session
              ON workflow_session.confirmed_result_id = suggestion.suggestion_id
             AND workflow_session.task_id = suggestion.task_id
             AND workflow_session.status = 'CONFIRMED'
             AND workflow_session.is_deleted = 0
            WHERE suggestion.task_id = #{taskId}
              AND suggestion.is_deleted = 0
            ORDER BY (workflow_session.id IS NOT NULL) DESC,
                     workflow_session.id DESC,
                     suggestion.update_time DESC,
                     suggestion.id DESC
            LIMIT 1
            """)
    @Results(id = "CreatorSuggestionRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "suggestion_id", property = "suggestionId"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "content_summary", property = "contentSummary"),
            @Result(column = "creator_dilemma", property = "creatorDilemma"),
            @Result(column = "audience_profile", property = "audienceProfile"),
            @Result(column = "audience_hook", property = "audienceHook"),
            @Result(column = "content_positioning", property = "contentPositioning"),
            @Result(column = "selling_points", property = "sellingPoints"),
            @Result(column = "risk_points", property = "riskPoints"),
            @Result(column = "title_suggestions", property = "titleSuggestions"),
            @Result(column = "description_suggestion", property = "descriptionSuggestion"),
            @Result(column = "actionable_revision_plan", property = "actionableRevisionPlan"),
            @Result(column = "tag_suggestions", property = "tagSuggestions"),
            @Result(column = "partition_suggestion", property = "partitionSuggestion"),
            @Result(column = "evidence_refs", property = "evidenceRefs"),
            @Result(column = "missing_info", property = "missingInfo"),
            @Result(column = "generation_mode", property = "generationMode"),
            @Result(column = "quality_status", property = "qualityStatus"),
            @Result(column = "audit_report", property = "auditReport"),
            @Result(column = "raw_output", property = "rawOutput"),
            @Result(column = "parse_status", property = "parseStatus"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<CreatorSuggestionRecord> findByTaskId(@Param("taskId") String taskId);

    /**
     * 按任务和工作流会话读取当前方案，避免旧会话晚完成后覆盖新会话页面。
     */
    @Select("""
            SELECT id,
                   suggestion_id,
                   task_id,
                   session_id,
                   content_summary,
                   creator_dilemma,
                   audience_profile,
                   audience_hook,
                   content_positioning,
                   selling_points,
                   risk_points,
                   title_suggestions,
                   description_suggestion,
                   actionable_revision_plan,
                   tag_suggestions,
                   partition_suggestion,
                   evidence_refs,
                   missing_info,
                   generation_mode,
                   quality_status,
                   audit_report,
                   raw_output,
                   parse_status,
                   create_time,
                   update_time
            FROM creator_suggestion
            WHERE task_id = #{taskId}
              AND session_id = #{sessionId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @ResultMap("CreatorSuggestionRecordMap")
    Optional<CreatorSuggestionRecord> findByTaskIdAndSessionId(@Param("taskId") String taskId,
                                                               @Param("sessionId") String sessionId);

    @Select("""
            SELECT id,
                   suggestion_id,
                   task_id,
                   session_id,
                   content_summary,
                   creator_dilemma,
                   audience_profile,
                   audience_hook,
                   content_positioning,
                   selling_points,
                   risk_points,
                   title_suggestions,
                   description_suggestion,
                   actionable_revision_plan,
                   tag_suggestions,
                   partition_suggestion,
                   evidence_refs,
                   missing_info,
                   generation_mode,
                   quality_status,
                   audit_report,
                   raw_output,
                   parse_status,
                   create_time,
                   update_time
            FROM creator_suggestion
            WHERE suggestion_id = #{suggestionId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @ResultMap("CreatorSuggestionRecordMap")
    Optional<CreatorSuggestionRecord> findBySuggestionId(@Param("suggestionId") String suggestionId);
}
