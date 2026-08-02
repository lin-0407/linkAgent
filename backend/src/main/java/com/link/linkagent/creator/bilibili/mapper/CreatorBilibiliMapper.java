package com.link.linkagent.creator.bilibili.mapper;

import com.link.linkagent.creator.bilibili.model.BilibiliAccountRecord;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoRecord;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingRecord;
import com.link.linkagent.creator.bilibili.model.VideoAnalysisReportRecord;
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
 * B站相关数据访问层（P0-3）。
 * 独立于已有 CreatorTaskMapper 和 CreatorInteractiveMapper，
 * 避免把账号、视频缓存、任务绑定和分析报告混入已有访问层，导致单一 Mapper 过大。
 * 全部使用注解 SQL，保持和项目其它 Mapper 一致的风格。
 * 查询返回对象均为普通 JavaBean，使用 @Results 按属性 setter 映射；
 * 持久化模型不能改为 Java record，否则 MyBatis 无法回填查询结果。
 */
@Mapper
public interface CreatorBilibiliMapper {

    // ══════════════════════════════════════════════════════════════
    // B站账号绑定（creator_bilibili_account）
    // ══════════════════════════════════════════════════════════════

    /**
     * 插入新的 B 站账号绑定记录。
     * uk_bilibili_user_id 保证每个平台用户只有一条绑定，重复插入会报唯一键冲突由上层处理。
     */
    @Insert("""
            INSERT INTO creator_bilibili_account (
                account_id,
                user_id,
                bilibili_uid,
                nickname,
                avatar_url,
                bind_status,
                last_sync_time,
                last_sync_error
            )
            VALUES (
                #{accountId},
                #{userId},
                #{bilibiliUid},
                #{nickname},
                #{avatarUrl},
                #{bindStatus},
                #{lastSyncTime},
                #{lastSyncError}
            )
            """)
    int insertAccount(BilibiliAccountRecord record);

    /**
     * 按平台用户 ID 查询 B 站账号绑定。
     * 只查未删除记录，LIMIT 1 因为 uk_bilibili_user_id 保证唯一。
     */
    @Select("""
            SELECT id,
                   account_id,
                   user_id,
                   bilibili_uid,
                   nickname,
                   avatar_url,
                   bind_status,
                   last_sync_time,
                   last_sync_error,
                   create_time,
                   update_time
            FROM creator_bilibili_account
            WHERE user_id = #{userId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "BilibiliAccountRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "account_id", property = "accountId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "bilibili_uid", property = "bilibiliUid"),
            @Result(column = "nickname", property = "nickname"),
            @Result(column = "avatar_url", property = "avatarUrl"),
            @Result(column = "bind_status", property = "bindStatus"),
            @Result(column = "last_sync_time", property = "lastSyncTime"),
            @Result(column = "last_sync_error", property = "lastSyncError"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<BilibiliAccountRecord> findAccountByUserId(@Param("userId") String userId);

    /**
     * 更新账号的同步结果。
     * 同步成功时更新昵称、头像和时间，失败时保留旧公开资料并更新错误信息和状态。
     * 不更新 bilibili_uid——UID 只能通过 bindAccount 接口修改，避免同步异常误改 UID。
     */
    @Update("""
            UPDATE creator_bilibili_account
            SET nickname = #{nickname},
                avatar_url = #{avatarUrl},
                bind_status = #{bindStatus},
                last_sync_time = #{lastSyncTime},
                last_sync_error = #{lastSyncError},
                update_time = CURRENT_TIMESTAMP
            WHERE account_id = #{accountId}
              AND is_deleted = 0
            """)
    int updateAccountSyncResult(@Param("accountId") String accountId,
                                @Param("nickname") String nickname,
                                @Param("avatarUrl") String avatarUrl,
                                @Param("bindStatus") String bindStatus,
                                @Param("lastSyncTime") LocalDateTime lastSyncTime,
                                @Param("lastSyncError") String lastSyncError);

    /**
     * 更新 B 站 UID。
     * 独立于 updateAccountSyncResult，因为 UID 只能通过 bindAccount 接口修改，
     * 同步流程不应有权限改动 UID。
     * 修改 UID 同时重置昵称、头像和同步信息，因为旧账号公开资料对新 UID 不再有效。
     */
    @Update("""
            UPDATE creator_bilibili_account
            SET bilibili_uid = #{bilibiliUid},
                nickname = NULL,
                avatar_url = NULL,
                last_sync_time = NULL,
                last_sync_error = NULL,
                bind_status = 'ACTIVE',
                update_time = CURRENT_TIMESTAMP
            WHERE account_id = #{accountId}
              AND is_deleted = 0
            """)
    int updateAccountUid(@Param("accountId") String accountId,
                         @Param("bilibiliUid") String bilibiliUid);

    // ══════════════════════════════════════════════════════════════
    // B站视频缓存（creator_bilibili_video）
    // ══════════════════════════════════════════════════════════════

    /**
     * 插入或更新 B 站视频缓存。
     * 使用 ON DUPLICATE KEY UPDATE 保证同一 BV + UID 组合只保留一条记录，
     * 后续同步时自动刷新视频信息和指标，避免手动删旧插新的并发问题。
     */
    @Insert("""
            INSERT INTO creator_bilibili_video (
                video_id,
                bilibili_uid,
                bvid,
                aid,
                title,
                cover_url,
                publish_time,
                view_count,
                like_count,
                coin_count,
                favorite_count,
                share_count,
                sync_status,
                last_sync_time,
                raw_snapshot
            )
            VALUES (
                #{videoId},
                #{bilibiliUid},
                #{bvid},
                #{aid},
                #{title},
                #{coverUrl},
                #{publishTime},
                #{viewCount},
                #{likeCount},
                #{coinCount},
                #{favoriteCount},
                #{shareCount},
                #{syncStatus},
                #{lastSyncTime},
                #{rawSnapshot}
            ) AS new_row
            ON DUPLICATE KEY UPDATE
                aid = new_row.aid,
                title = new_row.title,
                cover_url = new_row.cover_url,
                publish_time = new_row.publish_time,
                view_count = new_row.view_count,
                like_count = new_row.like_count,
                coin_count = new_row.coin_count,
                favorite_count = new_row.favorite_count,
                share_count = new_row.share_count,
                sync_status = new_row.sync_status,
                last_sync_time = new_row.last_sync_time,
                raw_snapshot = new_row.raw_snapshot,
                update_time = CURRENT_TIMESTAMP
            """)
    int insertVideo(BilibiliVideoRecord record);

    /**
     * 按 BV 号和 B 站 UID 查询视频缓存。
     * 用于判断某个 BV 是否已同步过，以及获取最新的视频信息。
     */
    @Select("""
            SELECT id,
                   video_id,
                   bilibili_uid,
                   bvid,
                   aid,
                   title,
                   cover_url,
                   publish_time,
                   view_count,
                   like_count,
                   coin_count,
                   favorite_count,
                   share_count,
                   sync_status,
                   last_sync_time,
                   raw_snapshot,
                   create_time,
                   update_time
            FROM creator_bilibili_video
            WHERE bvid = #{bvid}
              AND bilibili_uid = #{bilibiliUid}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "BilibiliVideoRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "video_id", property = "videoId"),
            @Result(column = "bilibili_uid", property = "bilibiliUid"),
            @Result(column = "bvid", property = "bvid"),
            @Result(column = "aid", property = "aid"),
            @Result(column = "title", property = "title"),
            @Result(column = "cover_url", property = "coverUrl"),
            @Result(column = "publish_time", property = "publishTime"),
            @Result(column = "view_count", property = "viewCount"),
            @Result(column = "like_count", property = "likeCount"),
            @Result(column = "coin_count", property = "coinCount"),
            @Result(column = "favorite_count", property = "favoriteCount"),
            @Result(column = "share_count", property = "shareCount"),
            @Result(column = "sync_status", property = "syncStatus"),
            @Result(column = "last_sync_time", property = "lastSyncTime"),
            @Result(column = "raw_snapshot", property = "rawSnapshot"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<BilibiliVideoRecord> findVideoByBvidAndUid(@Param("bvid") String bvid,
                                                        @Param("bilibiliUid") String bilibiliUid);

    /**
     * 列出某 B 站 UID 下所有已缓存的视频。
     * 按发布时间倒序，优先展示最新视频。
     */
    @Select("""
            SELECT id,
                   video_id,
                   bilibili_uid,
                   bvid,
                   aid,
                   title,
                   cover_url,
                   publish_time,
                   view_count,
                   like_count,
                   coin_count,
                   favorite_count,
                   share_count,
                   sync_status,
                   last_sync_time,
                   raw_snapshot,
                   create_time,
                   update_time
            FROM creator_bilibili_video
            WHERE bilibili_uid = #{bilibiliUid}
              AND is_deleted = 0
            ORDER BY publish_time DESC, create_time DESC
            """)
    @ResultMap("BilibiliVideoRecordMap")
    List<BilibiliVideoRecord> listVideosByUid(@Param("bilibiliUid") String bilibiliUid);

    /**
     * 批量查询指定 UID 下的一组 BV 视频缓存。
     * 用于已绑定视频列表组装，避免每条绑定单独查一次视频缓存造成 N+1 查询。
     */
    @Select("""
            <script>
            SELECT id,
                   video_id,
                   bilibili_uid,
                   bvid,
                   aid,
                   title,
                   cover_url,
                   publish_time,
                   view_count,
                   like_count,
                   coin_count,
                   favorite_count,
                   share_count,
                   sync_status,
                   last_sync_time,
                   raw_snapshot,
                   create_time,
                   update_time
            FROM creator_bilibili_video
            WHERE bilibili_uid = #{bilibiliUid}
              AND is_deleted = 0
              AND bvid IN
              <foreach collection="bvids" item="bvid" open="(" separator="," close=")">
                  #{bvid}
              </foreach>
            </script>
            """)
    @ResultMap("BilibiliVideoRecordMap")
    List<BilibiliVideoRecord> listVideosByBvidsAndUid(@Param("bvids") List<String> bvids,
                                                       @Param("bilibiliUid") String bilibiliUid);

    // ══════════════════════════════════════════════════════════════
    // 任务视频绑定（creator_task_video_binding）
    // ══════════════════════════════════════════════════════════════

    /**
     * 插入任务视频绑定记录。
     * uk_task_video_binding_task 保证每个任务最多一条绑定，重复插入会报唯一键冲突——
     * Service 层在插入前应先检查是否已有绑定，已有则直接返回而不是报错。
     */
    @Insert("""
            INSERT INTO creator_task_video_binding (
                binding_id,
                task_id,
                user_id,
                bilibili_uid,
                bvid,
                binding_status,
                verify_message
            )
            VALUES (
                #{bindingId},
                #{taskId},
                #{userId},
                #{bilibiliUid},
                #{bvid},
                #{bindingStatus},
                #{verifyMessage}
            )
            """)
    int insertBinding(TaskVideoBindingRecord record);

    /**
     * 按任务 ID 查询视频绑定。
     * 每个任务第一版只绑定一个 BV，LIMIT 1 做防护。
     */
    @Select("""
            SELECT id,
                   binding_id,
                   task_id,
                   user_id,
                   bilibili_uid,
                   bvid,
                   binding_status,
                   verify_message,
                   create_time,
                   update_time
            FROM creator_task_video_binding
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "TaskVideoBindingRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "binding_id", property = "bindingId"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "bilibili_uid", property = "bilibiliUid"),
            @Result(column = "bvid", property = "bvid"),
            @Result(column = "binding_status", property = "bindingStatus"),
            @Result(column = "verify_message", property = "verifyMessage"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<TaskVideoBindingRecord> findBindingByTaskId(@Param("taskId") String taskId);

    /**
     * 更新绑定状态和校验说明。
     * 用于 B 站视频同步后的自动校验：UID 匹配→BOUND，不匹配→UID_MISMATCH，视频不存在→VIDEO_NOT_FOUND。
     */
    @Update("""
            UPDATE creator_task_video_binding
            SET binding_status = #{bindingStatus},
                verify_message = #{verifyMessage},
                update_time = CURRENT_TIMESTAMP
            WHERE binding_id = #{bindingId}
              AND is_deleted = 0
            """)
    int updateBindingStatus(@Param("bindingId") String bindingId,
                            @Param("bindingStatus") String bindingStatus,
                            @Param("verifyMessage") String verifyMessage);

    /**
     * 修正尚未通过校验的任务视频绑定。
     *
     * 只有非 BOUND 状态允许走这个方法；已确认绑定继续由 Service 层保护，避免误覆盖已经验证通过的任务。
     */
    @Update("""
            UPDATE creator_task_video_binding
            SET bilibili_uid = #{bilibiliUid},
                bvid = #{bvid},
                binding_status = #{bindingStatus},
                verify_message = #{verifyMessage},
                update_time = CURRENT_TIMESTAMP
            WHERE binding_id = #{bindingId}
              AND is_deleted = 0
              AND (binding_status IS NULL OR binding_status <> 'BOUND')
            """)
    int updateBindingDetails(@Param("bindingId") String bindingId,
                             @Param("bilibiliUid") String bilibiliUid,
                             @Param("bvid") String bvid,
                             @Param("bindingStatus") String bindingStatus,
                             @Param("verifyMessage") String verifyMessage);

    /**
     * 按 BV 号查所有未删除绑定，用于冲突检测。
     * 当用户尝试把同一个 BV 绑定到第二个任务时，Service 层用此方法检查并给出警告。
     */
    @Select("""
            SELECT id,
                   binding_id,
                   task_id,
                   user_id,
                   bilibili_uid,
                   bvid,
                   binding_status,
                   verify_message,
                   create_time,
                   update_time
            FROM creator_task_video_binding
            WHERE bvid = #{bvid}
              AND is_deleted = 0
            """)
    @ResultMap("TaskVideoBindingRecordMap")
    List<TaskVideoBindingRecord> findBindingsByBvid(@Param("bvid") String bvid);

    /**
     * 列出某 B 站 UID 下所有未删除的任务视频绑定。
     * 用于视频分析页的"已绑定视频"列表——按绑定时间倒序。
     */
    @Select("""
            SELECT id,
                   binding_id,
                   task_id,
                   user_id,
                   bilibili_uid,
                   bvid,
                   binding_status,
                   verify_message,
                   create_time,
                   update_time
            FROM creator_task_video_binding
            WHERE bilibili_uid = #{bilibiliUid}
              AND is_deleted = 0
            ORDER BY create_time DESC
            """)
    @ResultMap("TaskVideoBindingRecordMap")
    List<TaskVideoBindingRecord> listBindingsByUid(@Param("bilibiliUid") String bilibiliUid);

    /**
     * 列出当前平台用户的全部任务视频绑定。
     *
     * 同步时不能只按当前 UID 查询：用户修改 UID 后，旧绑定仍需要被标记为 UID_MISMATCH，
     * 否则旧绑定会永远停留在 WAITING_VERIFY，用户也看不到具体原因。
     */
    @Select("""
            SELECT id,
                   binding_id,
                   task_id,
                   user_id,
                   bilibili_uid,
                   bvid,
                   binding_status,
                   verify_message,
                   create_time,
                   update_time
            FROM creator_task_video_binding
            WHERE user_id = #{userId}
              AND is_deleted = 0
            ORDER BY create_time DESC
            """)
    @ResultMap("TaskVideoBindingRecordMap")
    List<TaskVideoBindingRecord> listBindingsByUserId(@Param("userId") String userId);

    // ══════════════════════════════════════════════════════════════
    // 视频分析报告（creator_video_analysis_report）
    // ══════════════════════════════════════════════════════════════

    /**
     * 插入视频分析报告。
     * uk_video_analysis_task 保证每个任务只有一份分析报告。
     */
    @Insert("""
            INSERT INTO creator_video_analysis_report (
                analysis_id,
                task_id,
                bvid,
                workflow_session_id,
                analysis_status,
                one_sentence_summary,
                publish_plan_review,
                audience_focus,
                misunderstanding_points,
                controversy_points,
                next_action_plan,
                evidence_summary,
                raw_output,
                parse_status
            )
            VALUES (
                #{analysisId},
                #{taskId},
                #{bvid},
                #{workflowSessionId},
                #{analysisStatus},
                #{oneSentenceSummary},
                #{publishPlanReview},
                #{audienceFocus},
                #{misunderstandingPoints},
                #{controversyPoints},
                #{nextActionPlan},
                #{evidenceSummary},
                #{rawOutput},
                #{parseStatus}
            )
            """)
    int insertAnalysisReport(VideoAnalysisReportRecord record);

    /**
     * 按任务 ID 查询视频分析报告。
     * 每个任务只有一份报告，LIMIT 1 做防护。
     */
    @Select("""
            SELECT id,
                   analysis_id,
                   task_id,
                   bvid,
                   workflow_session_id,
                   analysis_status,
                   one_sentence_summary,
                   publish_plan_review,
                   audience_focus,
                   misunderstanding_points,
                   controversy_points,
                   next_action_plan,
                   evidence_summary,
                   raw_output,
                   parse_status,
                   create_time,
                   update_time
            FROM creator_video_analysis_report
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "VideoAnalysisReportRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "analysis_id", property = "analysisId"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "bvid", property = "bvid"),
            @Result(column = "workflow_session_id", property = "workflowSessionId"),
            @Result(column = "analysis_status", property = "analysisStatus"),
            @Result(column = "one_sentence_summary", property = "oneSentenceSummary"),
            @Result(column = "publish_plan_review", property = "publishPlanReview"),
            @Result(column = "audience_focus", property = "audienceFocus"),
            @Result(column = "misunderstanding_points", property = "misunderstandingPoints"),
            @Result(column = "controversy_points", property = "controversyPoints"),
            @Result(column = "next_action_plan", property = "nextActionPlan"),
            @Result(column = "evidence_summary", property = "evidenceSummary"),
            @Result(column = "raw_output", property = "rawOutput"),
            @Result(column = "parse_status", property = "parseStatus"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<VideoAnalysisReportRecord> findAnalysisReportByTaskId(@Param("taskId") String taskId);

    /**
     * 全字段更新视频分析报告（按 analysisId 匹配）。
     * 每次 LLM 重新分析后覆盖旧结果，不保留历史版本——
     * 如果需要版本对比，通过 creator_eval_result 表追溯。
     */
    @Update("""
            UPDATE creator_video_analysis_report
            SET analysis_status = #{analysisStatus},
                one_sentence_summary = #{oneSentenceSummary},
                publish_plan_review = #{publishPlanReview},
                audience_focus = #{audienceFocus},
                misunderstanding_points = #{misunderstandingPoints},
                controversy_points = #{controversyPoints},
                next_action_plan = #{nextActionPlan},
                evidence_summary = #{evidenceSummary},
                raw_output = #{rawOutput},
                parse_status = #{parseStatus},
                update_time = CURRENT_TIMESTAMP
            WHERE analysis_id = #{analysisId}
              AND is_deleted = 0
            """)
    int updateAnalysisReport(VideoAnalysisReportRecord record);

    /**
     * 批量按 taskId 列表查询任务记录。
     * 用于 getLinkedVideos 中避免逐条查询的 N+1 问题。
     * 列名 task_id / user_id 等通过 MyBatis mapUnderscoreToCamelCase 自动映射到驼峰属性，
     * 不需要显式 @Results 或跨 Mapper 引用 @ResultMap。
     */
    @Select("""
            <script>
            SELECT id, task_id, user_id, task_name, video_type, status, create_time, update_time
            FROM creator_task
            WHERE task_id IN
            <foreach collection="taskIds" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
              AND is_deleted = 0
            </script>
            """)
    List<com.link.linkagent.creator.task.model.CreatorTaskRecord> findTasksByTaskIds(@Param("taskIds") List<String> taskIds);
}
