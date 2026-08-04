package com.link.linkagent.memory;

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
 * 长期记忆 MySQL 访问层。
 * 当前阶段使用注解 SQL，是因为表结构简单，先降低 XML 配置成本。
 */
@Mapper
public interface LongTermMemoryMapper {

    @Insert("""
            INSERT INTO t_long_term_memory (user_id, memory_key, content, source_session_id, embedding_id)
            VALUES (#{userId}, #{memoryKey}, #{content}, #{sourceSessionId}, #{embeddingId})
            ON DUPLICATE KEY UPDATE
                content = VALUES(content),
                source_session_id = VALUES(source_session_id),
                embedding_id = VALUES(embedding_id),
                is_deleted = 0,
                update_time = CURRENT_TIMESTAMP
            """)
    int upsert(LongTermMemoryRecord record);

    @Select("""
            SELECT id, user_id, memory_key, content, source_session_id, embedding_id, create_time, update_time
            FROM t_long_term_memory
            WHERE user_id = #{userId}
              AND memory_key = #{memoryKey}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "LongTermMemoryRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "memory_key", property = "memoryKey"),
            @Result(column = "content", property = "content"),
            @Result(column = "source_session_id", property = "sourceSessionId"),
            @Result(column = "embedding_id", property = "embeddingId"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<LongTermMemoryRecord> findByKey(@Param("userId") String userId, @Param("memoryKey") String memoryKey);

    @Select("""
            SELECT id, user_id, memory_key, content, source_session_id, embedding_id, create_time, update_time
            FROM t_long_term_memory
            WHERE user_id = #{userId}
              AND is_deleted = 0
            ORDER BY update_time DESC, id DESC
            """)
    @Results(value = {
            @Result(column = "id", property = "id"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "memory_key", property = "memoryKey"),
            @Result(column = "content", property = "content"),
            @Result(column = "source_session_id", property = "sourceSessionId"),
            @Result(column = "embedding_id", property = "embeddingId"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    List<LongTermMemoryRecord> listByUser(@Param("userId") String userId);

    @Select("""
            SELECT id, user_id, memory_key, content, source_session_id, embedding_id, create_time, update_time
            FROM t_long_term_memory
            WHERE user_id = #{userId}
              AND is_deleted = 0
            ORDER BY update_time DESC, id DESC
            LIMIT #{limit}
            """)
    @Results(value = {
            @Result(column = "id", property = "id"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "memory_key", property = "memoryKey"),
            @Result(column = "content", property = "content"),
            @Result(column = "source_session_id", property = "sourceSessionId"),
            @Result(column = "embedding_id", property = "embeddingId"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    List<LongTermMemoryRecord> listRecentByUser(@Param("userId") String userId, @Param("limit") int limit);

    @Update("""
            UPDATE t_long_term_memory
            SET is_deleted = 1,
                update_time = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND memory_key = #{memoryKey}
              AND is_deleted = 0
            """)
    int softDelete(@Param("userId") String userId, @Param("memoryKey") String memoryKey);

    @Update("""
            UPDATE t_long_term_memory
            SET is_deleted = 0
            WHERE user_id = #{userId}
              AND memory_key = #{memoryKey}
              AND is_deleted = 1
            """)
    int restore(@Param("userId") String userId, @Param("memoryKey") String memoryKey);
}
