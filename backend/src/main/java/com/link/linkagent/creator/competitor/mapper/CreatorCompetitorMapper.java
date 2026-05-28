package com.link.linkagent.creator.competitor.mapper;

import com.link.linkagent.creator.competitor.model.CreatorCompetitorReportRecord;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSampleRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * 竞品视频和分析报告访问层。
 * 原始竞品视频材料与分析报告分表保存，便于后续重跑分析和人工检查。
 */
@Mapper
public interface CreatorCompetitorMapper {

    @Insert("""
            INSERT INTO creator_competitor_sample (
                competitor_bv_id,
                competitor_video_name,
                task_id,
                category,
                competitor_samples,
                compare_dimension,
                extra_context
            )
            VALUES (
                #{competitorBvId},
                #{competitorVideoName},
                #{taskId},
                #{category},
                #{competitorSamples},
                #{compareDimension},
                #{extraContext}
            )
            ON DUPLICATE KEY UPDATE
                competitor_bv_id = VALUES(competitor_bv_id),
                competitor_video_name = VALUES(competitor_video_name),
                category = VALUES(category),
                competitor_samples = VALUES(competitor_samples),
                compare_dimension = VALUES(compare_dimension),
                extra_context = VALUES(extra_context),
                is_deleted = 0,
                update_time = CURRENT_TIMESTAMP
            """)
    int upsertCompetitorVideo(CreatorCompetitorSampleRecord record);

    @Select("""
            SELECT id,
                   competitor_bv_id,
                   competitor_video_name,
                   task_id,
                   category,
                   competitor_samples,
                   compare_dimension,
                   extra_context,
                   create_time,
                   update_time
            FROM creator_competitor_sample
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "CreatorCompetitorSampleRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "competitor_bv_id", property = "competitorBvId"),
            @Result(column = "competitor_video_name", property = "competitorVideoName"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "category", property = "category"),
            @Result(column = "competitor_samples", property = "competitorSamples"),
            @Result(column = "compare_dimension", property = "compareDimension"),
            @Result(column = "extra_context", property = "extraContext"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<CreatorCompetitorSampleRecord> findCompetitorVideoByTaskId(@Param("taskId") String taskId);

    @Insert("""
            INSERT INTO creator_competitor_report (
                report_id,
                task_id,
                competitor_summary,
                competitor_advantages,
                own_advantages,
                own_disadvantages,
                gap_analysis,
                improvement_suggestions,
                differentiation_strategy,
                raw_output,
                parse_status
            )
            VALUES (
                #{reportId},
                #{taskId},
                #{competitorSummary},
                #{competitorAdvantages},
                #{ownAdvantages},
                #{ownDisadvantages},
                #{gapAnalysis},
                #{improvementSuggestions},
                #{differentiationStrategy},
                #{rawOutput},
                #{parseStatus}
            )
            ON DUPLICATE KEY UPDATE
                report_id = VALUES(report_id),
                competitor_summary = VALUES(competitor_summary),
                competitor_advantages = VALUES(competitor_advantages),
                own_advantages = VALUES(own_advantages),
                own_disadvantages = VALUES(own_disadvantages),
                gap_analysis = VALUES(gap_analysis),
                improvement_suggestions = VALUES(improvement_suggestions),
                differentiation_strategy = VALUES(differentiation_strategy),
                raw_output = VALUES(raw_output),
                parse_status = VALUES(parse_status),
                is_deleted = 0,
                update_time = CURRENT_TIMESTAMP
            """)
    int upsertReport(CreatorCompetitorReportRecord record);

    @Select("""
            SELECT id,
                   report_id,
                   task_id,
                   competitor_summary,
                   competitor_advantages,
                   own_advantages,
                   own_disadvantages,
                   gap_analysis,
                   improvement_suggestions,
                   differentiation_strategy,
                   raw_output,
                   parse_status,
                   create_time,
                   update_time
            FROM creator_competitor_report
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "CreatorCompetitorReportRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "report_id", property = "reportId"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "competitor_summary", property = "competitorSummary"),
            @Result(column = "competitor_advantages", property = "competitorAdvantages"),
            @Result(column = "own_advantages", property = "ownAdvantages"),
            @Result(column = "own_disadvantages", property = "ownDisadvantages"),
            @Result(column = "gap_analysis", property = "gapAnalysis"),
            @Result(column = "improvement_suggestions", property = "improvementSuggestions"),
            @Result(column = "differentiation_strategy", property = "differentiationStrategy"),
            @Result(column = "raw_output", property = "rawOutput"),
            @Result(column = "parse_status", property = "parseStatus"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<CreatorCompetitorReportRecord> findReportByTaskId(@Param("taskId") String taskId);
}
