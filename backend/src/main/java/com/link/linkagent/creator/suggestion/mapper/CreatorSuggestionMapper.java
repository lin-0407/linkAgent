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
 * 当前每个任务只保留一份最新建议，先满足演示闭环，后续再扩展版本管理。
 */
@Mapper
public interface CreatorSuggestionMapper {

    @Insert("""
            INSERT INTO creator_suggestion (
                suggestion_id,
                task_id,
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

    @Select("""
            SELECT id,
                   suggestion_id,
                   task_id,
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
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "CreatorSuggestionRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "suggestion_id", property = "suggestionId"),
            @Result(column = "task_id", property = "taskId"),
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

    @Select("""
            SELECT id,
                   suggestion_id,
                   task_id,
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
