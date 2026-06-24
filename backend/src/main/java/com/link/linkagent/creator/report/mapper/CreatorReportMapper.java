package com.link.linkagent.creator.report.mapper;

import com.link.linkagent.creator.report.model.CreatorReportRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * 创作复盘报告访问层。
 * 当前每个任务只保留一份最新复盘报告，先满足 MVP 闭环，后续需要对比时再引入版本表。
 */
@Mapper
public interface CreatorReportMapper {

    @Insert("""
            INSERT INTO creator_report (
                report_id,
                task_id,
                content_summary,
                core_selling_points,
                title_description_review,
                audience_feedback_summary,
                competitor_comparison,
                controversy_and_misunderstanding,
                next_action_suggestions,
                creator_preference_insight,
                overall_conclusion,
                raw_output,
                parse_status
            )
            VALUES (
                #{reportId},
                #{taskId},
                #{contentSummary},
                #{coreSellingPoints},
                #{titleDescriptionReview},
                #{audienceFeedbackSummary},
                #{competitorComparison},
                #{controversyAndMisunderstanding},
                #{nextActionSuggestions},
                #{creatorPreferenceInsight},
                #{overallConclusion},
                #{rawOutput},
                #{parseStatus}
            )
            ON DUPLICATE KEY UPDATE
                report_id = VALUES(report_id),
                content_summary = VALUES(content_summary),
                core_selling_points = VALUES(core_selling_points),
                title_description_review = VALUES(title_description_review),
                audience_feedback_summary = VALUES(audience_feedback_summary),
                competitor_comparison = VALUES(competitor_comparison),
                controversy_and_misunderstanding = VALUES(controversy_and_misunderstanding),
                next_action_suggestions = VALUES(next_action_suggestions),
                creator_preference_insight = VALUES(creator_preference_insight),
                overall_conclusion = VALUES(overall_conclusion),
                raw_output = VALUES(raw_output),
                parse_status = VALUES(parse_status),
                is_deleted = 0,
                update_time = CURRENT_TIMESTAMP
            """)
    int upsert(CreatorReportRecord record);

    @Select("""
            SELECT id,
                   report_id,
                   task_id,
                   content_summary,
                   core_selling_points,
                   title_description_review,
                   audience_feedback_summary,
                   competitor_comparison,
                   controversy_and_misunderstanding,
                   next_action_suggestions,
                   creator_preference_insight,
                   overall_conclusion,
                   raw_output,
                   parse_status,
                   create_time,
                   update_time
            FROM creator_report
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "CreatorReportRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "report_id", property = "reportId"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "content_summary", property = "contentSummary"),
            @Result(column = "core_selling_points", property = "coreSellingPoints"),
            @Result(column = "title_description_review", property = "titleDescriptionReview"),
            @Result(column = "audience_feedback_summary", property = "audienceFeedbackSummary"),
            @Result(column = "competitor_comparison", property = "competitorComparison"),
            @Result(column = "controversy_and_misunderstanding", property = "controversyAndMisunderstanding"),
            @Result(column = "next_action_suggestions", property = "nextActionSuggestions"),
            @Result(column = "creator_preference_insight", property = "creatorPreferenceInsight"),
            @Result(column = "overall_conclusion", property = "overallConclusion"),
            @Result(column = "raw_output", property = "rawOutput"),
            @Result(column = "parse_status", property = "parseStatus"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<CreatorReportRecord> findByTaskId(@Param("taskId") String taskId);
}
