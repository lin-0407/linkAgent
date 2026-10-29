package com.link.linkagent.creator.preference.mapper;

import com.link.linkagent.creator.preference.model.CreatorPreferenceRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 创作者长期偏好访问层。
 * 按用户、来源任务和来源记录保存，避免反馈历史与复盘快照竞争同一行。
 */
@Mapper
public interface CreatorPreferenceMapper {

    @Insert("""
            INSERT INTO creator_preference (
                preference_id,
                user_id,
                source_task_id,
                source_report_id,
                preference_content
            )
            VALUES (
                #{preferenceId},
                #{userId},
                #{sourceTaskId},
                #{sourceReportId},
                #{preferenceContent}
            )
            ON DUPLICATE KEY UPDATE
                source_report_id = VALUES(source_report_id),
                preference_content = VALUES(preference_content),
                is_deleted = 0,
                update_time = CURRENT_TIMESTAMP
            """)
    int upsert(CreatorPreferenceRecord record);

    @Select("""
            SELECT preference.id,
                   preference.preference_id,
                   preference.user_id,
                   preference.source_task_id,
                   task.task_name AS source_task_name,
                   preference.source_report_id,
                   preference.preference_content,
                   preference.create_time,
                   preference.update_time
            FROM creator_preference preference
            LEFT JOIN creator_task task ON task.task_id = preference.source_task_id
            WHERE preference.user_id = #{userId}
              AND preference.is_deleted = 0
            ORDER BY preference.update_time DESC, preference.id DESC
            LIMIT #{limit}
            """)
    @Results(id = "CreatorPreferenceRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "preference_id", property = "preferenceId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "source_task_id", property = "sourceTaskId"),
            @Result(column = "source_task_name", property = "sourceTaskName"),
            @Result(column = "source_report_id", property = "sourceReportId"),
            @Result(column = "preference_content", property = "preferenceContent"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    List<CreatorPreferenceRecord> listByUserId(@Param("userId") String userId, @Param("limit") int limit);

    /** 旧复盘仍可追溯，但只有最新报告对应的洞察继续参与偏好上下文。 */
    @Update("""
            UPDATE creator_preference
            SET is_deleted = 1, update_time = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND source_task_id = #{taskId}
              AND source_report_id <> #{reportId}
              AND LEFT(source_report_id, 18) <> 'ADOPTION_FEEDBACK_'
              AND is_deleted = 0
            """)
    int invalidateOtherReportPreferences(@Param("userId") String userId,
                                         @Param("taskId") String taskId,
                                         @Param("reportId") String reportId);

    @Select("""
            SELECT preference.id, preference.preference_id, preference.user_id,
                   preference.source_task_id, task.task_name AS source_task_name,
                   preference.source_report_id, preference.preference_content,
                   preference.create_time, preference.update_time
            FROM creator_preference preference
            LEFT JOIN creator_task task ON task.task_id = preference.source_task_id
            WHERE preference.user_id = #{userId}
              AND preference.is_deleted = 0
              AND LEFT(preference.source_report_id, 18) <> 'ADOPTION_FEEDBACK_'
            ORDER BY preference.update_time DESC, preference.id DESC
            LIMIT #{limit}
            """)
    @ResultMap("CreatorPreferenceRecordMap")
    List<CreatorPreferenceRecord> listReportPreferencesByUserId(@Param("userId") String userId,
                                                               @Param("limit") int limit);

    /**
     * 插入采用/拒绝反馈记录。
     * 每次反馈使用独立来源标识，保留同任务多次采用和拒绝的历史。
     */
    @Insert("""
            INSERT INTO creator_preference (
                preference_id,
                user_id,
                source_task_id,
                source_report_id,
                preference_content
            )
            VALUES (
                #{preferenceId},
                #{userId},
                #{sourceTaskId},
                #{sourceReportId},
                #{preferenceContent}
            )
            ON DUPLICATE KEY UPDATE
                source_report_id = VALUES(source_report_id),
                preference_content = VALUES(preference_content),
                is_deleted = 0,
                update_time = CURRENT_TIMESTAMP
            """)
    int upsertAdoptionFeedback(CreatorPreferenceRecord record);

    /**
     * 查询指定用户最近的采用/拒绝反馈记录。
     * source_report_id 以 ADOPTION_FEEDBACK_ 开头的记录是采用反馈而非复盘洞察。
     */
    @Select("""
            SELECT preference.id,
                   preference.preference_id,
                   preference.user_id,
                   preference.source_task_id,
                   task.task_name AS source_task_name,
                   preference.source_report_id,
                   preference.preference_content,
                   preference.create_time,
                   preference.update_time
            FROM creator_preference preference
            LEFT JOIN creator_task task ON task.task_id = preference.source_task_id
            WHERE preference.user_id = #{userId}
              AND preference.is_deleted = 0
              AND LEFT(preference.source_report_id, 18) = 'ADOPTION_FEEDBACK_'
            ORDER BY preference.update_time DESC, preference.id DESC
            LIMIT #{limit}
            """)
    @ResultMap("CreatorPreferenceRecordMap")
    List<CreatorPreferenceRecord> listAdoptionFeedbackByUserId(@Param("userId") String userId, @Param("limit") int limit);
}
