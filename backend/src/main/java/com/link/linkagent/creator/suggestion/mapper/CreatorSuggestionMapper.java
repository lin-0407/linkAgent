package com.link.linkagent.creator.suggestion.mapper;

import com.link.linkagent.creator.suggestion.model.CreatorSuggestionRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
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
                audience_profile,
                selling_points,
                risk_points,
                title_suggestions,
                description_suggestion,
                tag_suggestions,
                partition_suggestion,
                raw_output,
                parse_status
            )
            VALUES (
                #{suggestionId},
                #{taskId},
                #{contentSummary},
                #{audienceProfile},
                #{sellingPoints},
                #{riskPoints},
                #{titleSuggestions},
                #{descriptionSuggestion},
                #{tagSuggestions},
                #{partitionSuggestion},
                #{rawOutput},
                #{parseStatus}
            )
            ON DUPLICATE KEY UPDATE
                suggestion_id = VALUES(suggestion_id),
                content_summary = VALUES(content_summary),
                audience_profile = VALUES(audience_profile),
                selling_points = VALUES(selling_points),
                risk_points = VALUES(risk_points),
                title_suggestions = VALUES(title_suggestions),
                description_suggestion = VALUES(description_suggestion),
                tag_suggestions = VALUES(tag_suggestions),
                partition_suggestion = VALUES(partition_suggestion),
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
                   audience_profile,
                   selling_points,
                   risk_points,
                   title_suggestions,
                   description_suggestion,
                   tag_suggestions,
                   partition_suggestion,
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
            @Result(column = "audience_profile", property = "audienceProfile"),
            @Result(column = "selling_points", property = "sellingPoints"),
            @Result(column = "risk_points", property = "riskPoints"),
            @Result(column = "title_suggestions", property = "titleSuggestions"),
            @Result(column = "description_suggestion", property = "descriptionSuggestion"),
            @Result(column = "tag_suggestions", property = "tagSuggestions"),
            @Result(column = "partition_suggestion", property = "partitionSuggestion"),
            @Result(column = "raw_output", property = "rawOutput"),
            @Result(column = "parse_status", property = "parseStatus"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<CreatorSuggestionRecord> findByTaskId(@Param("taskId") String taskId);
}
