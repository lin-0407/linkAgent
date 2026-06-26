package com.link.linkagent.creator.profile.mapper;

import com.link.linkagent.creator.profile.model.CreatorEventRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 创作者事件流水访问层。
 * 事件是只追加的不可变记录，不提供 update/delete 方法，保证事件流完整性。
 */
@Mapper
public interface CreatorEventMapper {

    @Insert("""
            INSERT INTO creator_event (
                event_id,
                creator_id,
                event_type,
                task_id,
                payload
            )
            VALUES (
                #{eventId},
                #{creatorId},
                #{eventType},
                #{taskId},
                #{payload}
            )
            """)
    int insert(CreatorEventRecord record);

    /**
     * 查询指定用户自某时间点以来的新事件数。
     * 用于判断是否达到画像更新的触发阈值。
     */
    @Select("""
            SELECT COUNT(1)
            FROM creator_event
            WHERE creator_id = #{creatorId}
              AND is_deleted = 0
              AND created_at > #{since}
            """)
    int countNewEvents(@Param("creatorId") String creatorId, @Param("since") java.time.LocalDateTime since);

    /**
     * 查询指定用户最近的事件列表，用于 LLM 增量更新画像。
     * 只取最近 N 条，避免上下文过长。
     */
    @Select("""
            SELECT id,
                   event_id,
                   creator_id,
                   event_type,
                   task_id,
                   payload,
                   created_at
            FROM creator_event
            WHERE creator_id = #{creatorId}
              AND is_deleted = 0
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    @Results(id = "CreatorEventRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "event_id", property = "eventId"),
            @Result(column = "creator_id", property = "creatorId"),
            @Result(column = "event_type", property = "eventType"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "payload", property = "payload"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<CreatorEventRecord> listRecentByCreator(@Param("creatorId") String creatorId, @Param("limit") int limit);
}
