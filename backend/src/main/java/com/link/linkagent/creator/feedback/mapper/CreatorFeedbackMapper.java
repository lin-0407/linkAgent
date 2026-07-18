package com.link.linkagent.creator.feedback.mapper;

import com.link.linkagent.creator.feedback.model.CreatorFeedbackRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackDashboardStatRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackItemRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackMetricRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportRecord;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackStatRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
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
                creator_feedback_dilemma,
                audience_core_concern,
                misunderstanding_source_analysis,
                feedback_action_plan,
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
                #{creatorFeedbackDilemma},
                #{audienceCoreConcern},
                #{misunderstandingSourceAnalysis},
                #{feedbackActionPlan},
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
                creator_feedback_dilemma = VALUES(creator_feedback_dilemma),
                audience_core_concern = VALUES(audience_core_concern),
                misunderstanding_source_analysis = VALUES(misunderstanding_source_analysis),
                feedback_action_plan = VALUES(feedback_action_plan),
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
                   creator_feedback_dilemma,
                   audience_core_concern,
                   misunderstanding_source_analysis,
                   feedback_action_plan,
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
            @Result(column = "creator_feedback_dilemma", property = "creatorFeedbackDilemma"),
            @Result(column = "audience_core_concern", property = "audienceCoreConcern"),
            @Result(column = "misunderstanding_source_analysis", property = "misunderstandingSourceAnalysis"),
            @Result(column = "feedback_action_plan", property = "feedbackActionPlan"),
            @Result(column = "raw_output", property = "rawOutput"),
            @Result(column = "parse_status", property = "parseStatus"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<CreatorFeedbackReportRecord> findReportByTaskId(@Param("taskId") String taskId);

    /**
     * 使用逻辑删除保留历史导入痕迹，是为了后续做失败回放或导入审计时有扩展空间。
     */
    @Update("""
            UPDATE creator_feedback_item
            SET is_deleted = 1
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            """)
    int softDeleteItemsByTaskId(@Param("taskId") String taskId);

    /**
     * 指标也跟随导入批次逻辑删除，避免前端把上一批文件的播放数据混到新样例里。
     */
    @Update("""
            UPDATE creator_feedback_metric
            SET is_deleted = 1
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            """)
    int softDeleteMetricByTaskId(@Param("taskId") String taskId);

    /**
     * 明细逐条插入而不是整包 JSON 入库，是为了让分类统计、筛选和后续证据检索都能直接基于 SQL 完成。
     */
    @Insert("""
            INSERT INTO creator_feedback_item (
                item_id,
                task_id,
                source_type,
                source_id,
                content,
                occur_time_text,
                like_count,
                reply_count,
                category,
                sentiment,
                is_noise,
                reason
            )
            VALUES (
                #{itemId},
                #{taskId},
                #{sourceType},
                #{sourceId},
                #{content},
                #{occurTimeText},
                #{likeCount},
                #{replyCount},
                #{category},
                #{sentiment},
                #{noise},
                #{reason}
            )
            """)
    int insertItem(CreatorFeedbackItemRecord record);

    /**
     * 指标和明细分开保存，因为播放、点赞等指标缺失时不能影响评论弹幕明细导入。
     */
    @Insert("""
            INSERT INTO creator_feedback_metric (
                metric_id,
                task_id,
                view_count,
                favorite_count,
                coin_count,
                like_count,
                share_count,
                source
            )
            VALUES (
                #{metricId},
                #{taskId},
                #{viewCount},
                #{favoriteCount},
                #{coinCount},
                #{likeCount},
                #{shareCount},
                #{source}
            )
            ON DUPLICATE KEY UPDATE
                metric_id = VALUES(metric_id),
                view_count = VALUES(view_count),
                favorite_count = VALUES(favorite_count),
                coin_count = VALUES(coin_count),
                like_count = VALUES(like_count),
                share_count = VALUES(share_count),
                source = VALUES(source),
                is_deleted = 0,
                create_time = CURRENT_TIMESTAMP
            """)
    int upsertMetric(CreatorFeedbackMetricRecord record);

    @Select("""
            SELECT id,
                   item_id,
                   task_id,
                   source_type,
                   source_id,
                   content,
                   occur_time_text,
                   like_count,
                   reply_count,
                   category,
                   sentiment,
                   is_noise,
                   reason,
                   create_time
            FROM creator_feedback_item
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    @Results(id = "CreatorFeedbackItemRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "item_id", property = "itemId"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "source_type", property = "sourceType"),
            @Result(column = "source_id", property = "sourceId"),
            @Result(column = "content", property = "content"),
            @Result(column = "occur_time_text", property = "occurTimeText"),
            @Result(column = "like_count", property = "likeCount"),
            @Result(column = "reply_count", property = "replyCount"),
            @Result(column = "category", property = "category"),
            @Result(column = "sentiment", property = "sentiment"),
            @Result(column = "is_noise", property = "noise"),
            @Result(column = "reason", property = "reason"),
            @Result(column = "create_time", property = "createTime")
    })
    List<CreatorFeedbackItemRecord> listItemsByTaskId(@Param("taskId") String taskId, @Param("limit") int limit);

    /**
     * 高赞评论单独查询，是因为点赞量比普通时间顺序更能体现观众共鸣和复盘优先级。
     */
    @Select("""
            SELECT id,
                   item_id,
                   task_id,
                   source_type,
                   source_id,
                   content,
                   occur_time_text,
                   like_count,
                   reply_count,
                   category,
                   sentiment,
                   is_noise,
                   reason,
                   create_time
            FROM creator_feedback_item
            WHERE task_id = #{taskId}
              AND source_type = 'COMMENT'
              AND is_noise = 0
              AND is_deleted = 0
            ORDER BY COALESCE(like_count, 0) DESC,
                     COALESCE(reply_count, 0) DESC,
                     id DESC
            LIMIT #{limit}
            """)
    @ResultMap("CreatorFeedbackItemRecordMap")
    List<CreatorFeedbackItemRecord> listTopCommentItemsByTaskId(@Param("taskId") String taskId,
                                                                @Param("limit") int limit);

    @Select("""
            SELECT COUNT(1)
            FROM creator_feedback_item
            WHERE task_id = #{taskId}
              AND source_type = #{sourceType}
              AND is_deleted = 0
            """)
    long countItemsBySourceType(@Param("taskId") String taskId, @Param("sourceType") String sourceType);

    @Select("""
            SELECT COUNT(1)
            FROM creator_feedback_item
            WHERE task_id = #{taskId}
              AND is_noise = 1
              AND is_deleted = 0
            """)
    long countNoiseItems(@Param("taskId") String taskId);

    @Select("""
            SELECT category AS name,
                   COUNT(1) AS count
            FROM creator_feedback_item
            WHERE task_id = #{taskId}
              AND source_type = #{sourceType}
              AND is_deleted = 0
            GROUP BY category
            ORDER BY count DESC, category ASC
            """)
    @Results(id = "CreatorFeedbackStatRecordMap", value = {
            @Result(column = "name", property = "name"),
            @Result(column = "count", property = "count")
    })
    List<CreatorFeedbackStatRecord> countCategoryStats(@Param("taskId") String taskId,
                                                       @Param("sourceType") String sourceType);

    @Select("""
            SELECT sentiment AS name,
                   COUNT(1) AS count
            FROM creator_feedback_item
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            GROUP BY sentiment
            ORDER BY count DESC, sentiment ASC
            """)
    @ResultMap("CreatorFeedbackStatRecordMap")
    List<CreatorFeedbackStatRecord> countSentimentStats(@Param("taskId") String taskId);

    /**
     * 一次返回仪表盘需要的主要聚合统计。
     * 多个 UNION 分支仍保持统计语义清晰，但只发生一次 Mapper 调用，减少页面打开时的 SQL 往返。
     */
    @Select("""
            SELECT 'SOURCE' AS stat_scope,
                   source_type AS name,
                   COUNT(1) AS count
            FROM creator_feedback_item
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            GROUP BY source_type
            UNION ALL
            SELECT 'NOISE' AS stat_scope,
                   'NOISE' AS name,
                   COUNT(1) AS count
            FROM creator_feedback_item
            WHERE task_id = #{taskId}
              AND is_noise = 1
              AND is_deleted = 0
            UNION ALL
            SELECT 'COMMENT_CATEGORY' AS stat_scope,
                   category AS name,
                   COUNT(1) AS count
            FROM creator_feedback_item
            WHERE task_id = #{taskId}
              AND source_type = 'COMMENT'
              AND is_deleted = 0
            GROUP BY category
            UNION ALL
            SELECT 'DANMAKU_CATEGORY' AS stat_scope,
                   category AS name,
                   COUNT(1) AS count
            FROM creator_feedback_item
            WHERE task_id = #{taskId}
              AND source_type = 'DANMAKU'
              AND is_deleted = 0
            GROUP BY category
            UNION ALL
            SELECT 'SENTIMENT' AS stat_scope,
                   sentiment AS name,
                   COUNT(1) AS count
            FROM creator_feedback_item
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            GROUP BY sentiment
            """)
    @Results(id = "CreatorFeedbackDashboardStatRecordMap", value = {
            @Result(column = "stat_scope", property = "statScope"),
            @Result(column = "name", property = "name"),
            @Result(column = "count", property = "count")
    })
    List<CreatorFeedbackDashboardStatRecord> listDashboardStats(@Param("taskId") String taskId);

    @Select("""
            SELECT id,
                   metric_id,
                   task_id,
                   view_count,
                   favorite_count,
                   coin_count,
                   like_count,
                   share_count,
                   source,
                   create_time
            FROM creator_feedback_metric
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "CreatorFeedbackMetricRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "metric_id", property = "metricId"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "view_count", property = "viewCount"),
            @Result(column = "favorite_count", property = "favoriteCount"),
            @Result(column = "coin_count", property = "coinCount"),
            @Result(column = "like_count", property = "likeCount"),
            @Result(column = "share_count", property = "shareCount"),
            @Result(column = "source", property = "source"),
            @Result(column = "create_time", property = "createTime")
    })
    Optional<CreatorFeedbackMetricRecord> findMetricByTaskId(@Param("taskId") String taskId);

    // ============================ 阶段 4.13 反馈证据向量索引 ============================

    /**
     * 查询当前任务可索引的评论弹幕明细。
     * <p>
     * 不在导入时自动索引全部明细，而是由用户显式触发重建并带 limit，是为了让演示环境的 Embedding 成本可控。
     * includeNoise=false 时排除噪声明细，避免把“哈哈哈”等无意义内容也写进向量库浪费 Embedding。
     */
    @Select("""
            <script>
            SELECT id, item_id, task_id, source_type, source_id, content,
                   occur_time_text, like_count, reply_count, category,
                   sentiment, is_noise, reason, create_time
            FROM creator_feedback_item
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            <if test='!includeNoise'>
              AND is_noise = 0
            </if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    @ResultMap("CreatorFeedbackItemRecordMap")
    List<CreatorFeedbackItemRecord> listIndexableItemsByTaskId(@Param("taskId") String taskId,
                                                               @Param("limit") int limit,
                                                               @Param("includeNoise") boolean includeNoise);

    /**
     * 标记某条明细已成功写入向量库。
     * <p>
     * 带 task_id 和 is_deleted = 0 条件，避免向量库回传的旧 item_id 串改到历史导入批次或已删除明细。
     */
    @Update("""
            UPDATE creator_feedback_item
            SET embedding_id = #{embeddingId},
                embedding_status = 'INDEXED',
                embedding_error = NULL,
                embedding_update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
              AND item_id = #{itemId}
              AND is_deleted = 0
            """)
    int updateItemEmbeddingIndexed(@Param("taskId") String taskId,
                                   @Param("itemId") String itemId,
                                   @Param("embeddingId") String embeddingId);

    /**
     * 标记某条明细索引失败，并保存截断后的失败原因摘要，便于排查 Embedding 或 Milvus 异常。
     */
    @Update("""
            UPDATE creator_feedback_item
            SET embedding_status = 'FAILED',
                embedding_error = #{errorMessage},
                embedding_update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
              AND item_id = #{itemId}
              AND is_deleted = 0
            """)
    int updateItemEmbeddingFailed(@Param("taskId") String taskId,
                                  @Param("itemId") String itemId,
                                  @Param("errorMessage") String errorMessage);

    /**
     * 按向量索引状态分组计数，复用 CreatorFeedbackStatRecord（name=状态，count=数量）。
     * 复用而不新建结构，是因为索引状态计数和分类/情绪计数本质上是同一种“枚举计数”形态。
     */
    @Select("""
            SELECT embedding_status AS name,
                   COUNT(1) AS count
            FROM creator_feedback_item
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            GROUP BY embedding_status
            """)
    @ResultMap("CreatorFeedbackStatRecordMap")
    List<CreatorFeedbackStatRecord> countEmbeddingStatusByTaskId(@Param("taskId") String taskId);

    /**
     * 查询当前任务最近一次成功索引时间（仅看 INDEXED 状态），用于状态展示的 lastIndexedAt。
     * 没有任何成功索引时返回 null。
     */
    @Select("""
            SELECT MAX(embedding_update_time)
            FROM creator_feedback_item
            WHERE task_id = #{taskId}
              AND embedding_status = 'INDEXED'
              AND is_deleted = 0
            """)
    LocalDateTime findLastEmbeddingUpdateTime(@Param("taskId") String taskId);

    /**
     * 按 task_id + item_id 列表回查 MySQL 当前有效明细。
     * <p>
     * 这是 RAG 的“事实来源回查”：Milvus 只负责找出候选 item_id，最终证据必须用 task_id 和 is_deleted = 0
     * 回查 MySQL，保证向量库里的旧导入批次文档或已删除明细不会进入回答。调用方需保证 itemIds 非空。
     */
    @Select("""
            <script>
            SELECT id, item_id, task_id, source_type, source_id, content,
                   occur_time_text, like_count, reply_count, category,
                   sentiment, is_noise, reason, create_time
            FROM creator_feedback_item
            WHERE task_id = #{taskId}
              AND is_deleted = 0
              AND item_id IN
              <foreach item='itemId' collection='itemIds' open='(' separator=',' close=')'>
                  #{itemId}
              </foreach>
            </script>
            """)
    @ResultMap("CreatorFeedbackItemRecordMap")
    List<CreatorFeedbackItemRecord> listItemsByTaskIdAndItemIds(@Param("taskId") String taskId,
                                                                @Param("itemIds") List<String> itemIds);
}
