package com.link.linkagent.creator.task.mapper;

import com.link.linkagent.creator.task.model.PrePublishSettingsRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface CreatorPrePublishSettingsMapper {

    @Select("""
            SELECT COUNT(*)
            FROM creator_task
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            """)
    int countTask(@Param("taskId") String taskId);

    @Select("""
            SELECT id,
                   task_id,
                   preference_mode,
                   creator_preference,
                   title_style,
                   extra_requirement,
                   custom_guidance,
                   create_time,
                   update_time
            FROM creator_pre_publish_setting
            WHERE task_id = #{taskId}
            LIMIT 1
            """)
    @Results(id = "PrePublishSettingsRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "preference_mode", property = "preferenceMode"),
            @Result(column = "creator_preference", property = "creatorPreference"),
            @Result(column = "title_style", property = "titleStyle"),
            @Result(column = "extra_requirement", property = "extraRequirement"),
            @Result(column = "custom_guidance", property = "customGuidance"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<PrePublishSettingsRecord> findByTaskId(@Param("taskId") String taskId);

    @Insert("""
            INSERT INTO creator_pre_publish_setting (
                task_id,
                preference_mode,
                creator_preference,
                title_style,
                extra_requirement,
                custom_guidance
            ) VALUES (
                #{taskId},
                #{preferenceMode},
                #{creatorPreference},
                #{titleStyle},
                #{extraRequirement},
                #{customGuidance}
            )
            ON DUPLICATE KEY UPDATE
                preference_mode = VALUES(preference_mode),
                creator_preference = VALUES(creator_preference),
                title_style = VALUES(title_style),
                extra_requirement = VALUES(extra_requirement),
                custom_guidance = VALUES(custom_guidance),
                update_time = CURRENT_TIMESTAMP
            """)
    int upsert(PrePublishSettingsRecord record);
}
