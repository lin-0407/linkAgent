package com.link.linkagent.creator.feedback.mapper;

import com.link.linkagent.creator.feedback.model.CreatorFeedbackRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * 评论弹幕反馈访问层。
 * 样例和报告分表保存，是为了让原始反馈与 LLM 分析结果可以独立排查。
 */
@Mapper
public interface CreatorFeedbackMapper {

    @Insert("""
            INSERT INTO creator_user_feedback_detail (
                feedback_id,
                task_id,
                comment_samples,
                danmaku_samples,
                extra_context
            )
            VALUES (
                #{feedbackId},
                #{taskId},
                #{commentSamples},
                #{danmakuSamples},
                #{extraContext}
            )
            ON DUPLICATE KEY UPDATE
                feedback_id = VALUES(feedback_id),
                comment_samples = VALUES(comment_samples),
                danmaku_samples = VALUES(danmaku_samples),
                extra_context = VALUES(extra_context),
                is_deleted = 0,
                update_time = CURRENT_TIMESTAMP
            """)
    int upsertFeedback(CreatorFeedbackRecord record);

    @Select("""
            SELECT id,
                   feedback_id,
                   task_id,
                   comment_samples,
                   danmaku_samples,
                   extra_context,
                   create_time,
                   update_time
            FROM creator_user_feedback_detail
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "CreatorFeedbackRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "feedback_id", property = "feedbackId"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "comment_samples", property = "commentSamples"),
            @Result(column = "danmaku_samples", property = "danmakuSamples"),
            @Result(column = "extra_context", property = "extraContext"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<CreatorFeedbackRecord> findFeedbackByTaskId(@Param("taskId") String taskId);

    @Insert("""
            INSERT INTO creator_llm_feedback_report (
                report_id,
                task_id,
                feedback_summary,
                hot_topics,
                sentiment_summary,
                controversy_points,
                misunderstanding_points,
                next_content_suggestions,
                interaction_suggestions,
                raw_output,
                parse_status
            )
            VALUES (
                #{reportId},
                #{taskId},
                #{feedbackSummary},
                #{hotTopics},
                #{sentimentSummary},
                #{controversyPoints},
                #{misunderstandingPoints},
                #{nextContentSuggestions},
                #{interactionSuggestions},
                #{rawOutput},
                #{parseStatus}
            )
            ON DUPLICATE KEY UPDATE
                report_id = VALUES(report_id),
                feedback_summary = VALUES(feedback_summary),
                hot_topics = VALUES(hot_topics),
                sentiment_summary = VALUES(sentiment_summary),
                controversy_points = VALUES(controversy_points),
                misunderstanding_points = VALUES(misunderstanding_points),
                next_content_suggestions = VALUES(next_content_suggestions),
                interaction_suggestions = VALUES(interaction_suggestions),
                raw_output = VALUES(raw_output),
                parse_status = VALUES(parse_status),
                is_deleted = 0,
                update_time = CURRENT_TIMESTAMP
            """)
    int upsertReport(CreatorFeedbackReportRecord record);

    @Select("""
            SELECT id,
                   report_id,
                   task_id,
                   feedback_summary,
                   hot_topics,
                   sentiment_summary,
                   controversy_points,
                   misunderstanding_points,
                   next_content_suggestions,
                   interaction_suggestions,
                   raw_output,
                   parse_status,
                   create_time,
                   update_time
            FROM creator_llm_feedback_report
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "CreatorFeedbackReportRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "report_id", property = "reportId"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "feedback_summary", property = "feedbackSummary"),
            @Result(column = "hot_topics", property = "hotTopics"),
            @Result(column = "sentiment_summary", property = "sentimentSummary"),
            @Result(column = "controversy_points", property = "controversyPoints"),
            @Result(column = "misunderstanding_points", property = "misunderstandingPoints"),
            @Result(column = "next_content_suggestions", property = "nextContentSuggestions"),
            @Result(column = "interaction_suggestions", property = "interactionSuggestions"),
            @Result(column = "raw_output", property = "rawOutput"),
            @Result(column = "parse_status", property = "parseStatus"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<CreatorFeedbackReportRecord> findReportByTaskId(@Param("taskId") String taskId);
}
