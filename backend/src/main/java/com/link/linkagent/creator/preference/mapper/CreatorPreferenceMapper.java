package com.link.linkagent.creator.preference.mapper;

import com.link.linkagent.creator.preference.model.CreatorPreferenceRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 创作者长期偏好访问层。
 * 当前按用户和来源任务保存快照，避免同一期复盘重复生成时堆积重复偏好。
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
            SELECT id,
                   preference_id,
                   user_id,
                   source_task_id,
                   source_report_id,
                   preference_content,
                   create_time,
                   update_time
            FROM creator_preference
            WHERE user_id = #{userId}
              AND is_deleted = 0
            ORDER BY update_time DESC, id DESC
            LIMIT #{limit}
            """)
    @Results(id = "CreatorPreferenceRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "preference_id", property = "preferenceId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "source_task_id", property = "sourceTaskId"),
            @Result(column = "source_report_id", property = "sourceReportId"),
            @Result(column = "preference_content", property = "preferenceContent"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    List<CreatorPreferenceRecord> listByUserId(@Param("userId") String userId, @Param("limit") int limit);
}
