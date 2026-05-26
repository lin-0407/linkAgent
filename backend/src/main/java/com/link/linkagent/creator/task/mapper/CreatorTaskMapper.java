package com.link.linkagent.creator.task.mapper;

import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.creator.task.model.CreatorTaskSummaryRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * 创作任务 MySQL 访问层。
 * 阶段 4.1 沿用注解 SQL，是为了先跑通任务输入闭环，后续 SQL 变复杂再考虑 XML。
 */
@Mapper
public interface CreatorTaskMapper {

    @Insert("""
            INSERT INTO creator_task (task_id, user_id, task_name, status)
            VALUES (#{taskId}, #{userId}, #{taskName}, #{status})
            """)
    int insertTask(CreatorTaskRecord record);

    @Insert("""
            INSERT INTO creator_material (task_id, material_type, content)
            VALUES (#{taskId}, #{materialType}, #{content})
            ON DUPLICATE KEY UPDATE
                content = VALUES(content),
                is_deleted = 0,
                update_time = CURRENT_TIMESTAMP
            """)
    int upsertMaterial(CreatorMaterialRecord record);

    @Select("""
            SELECT id, task_id, user_id, task_name, status, create_time, update_time
            FROM creator_task
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "CreatorTaskRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "task_name", property = "taskName"),
            @Result(column = "status", property = "status"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<CreatorTaskRecord> findTaskByTaskId(@Param("taskId") String taskId);

    @Select("""
            SELECT id, task_id, material_type, content, create_time, update_time
            FROM creator_material
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            ORDER BY id ASC
            """)
    @Results(id = "CreatorMaterialRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "material_type", property = "materialType"),
            @Result(column = "content", property = "content"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    List<CreatorMaterialRecord> listMaterialsByTaskId(@Param("taskId") String taskId);

    @Select("""
            SELECT task.id,
                   task.task_id,
                   task.user_id,
                   task.task_name,
                   task.status,
                   COUNT(material.id) AS material_count,
                   task.create_time,
                   task.update_time
            FROM creator_task task
            LEFT JOIN creator_material material
                   ON task.task_id = material.task_id
                  AND material.is_deleted = 0
            WHERE task.user_id = #{userId}
              AND task.is_deleted = 0
            GROUP BY task.id, task.task_id, task.user_id, task.task_name, task.status, task.create_time, task.update_time
            ORDER BY task.update_time DESC, task.id DESC
            LIMIT #{limit}
            """)
    @Results(id = "CreatorTaskSummaryRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "task_name", property = "taskName"),
            @Result(column = "status", property = "status"),
            @Result(column = "material_count", property = "materialCount"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    List<CreatorTaskSummaryRecord> listTasksByUser(@Param("userId") String userId, @Param("limit") int limit);

    @Update("""
            UPDATE creator_task
            SET status = #{status},
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            """)
    int updateTaskStatus(@Param("taskId") String taskId, @Param("status") String status);
}
