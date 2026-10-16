package com.link.linkagent.memory;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 长期记忆 MySQL 访问层。
 * <p>
 * 记录身份是自增主键 id（设计文档 D13 / E1）：写入一律新增，不再按 (user_id, memory_key) 覆盖。
 * memory_key 只用于分类展示，同一 key 下允许多条不同事实并存。
 * <p>
 * 当前阶段使用注解 SQL，是因为表结构简单，先降低 XML 配置成本。
 */
@Mapper
public interface LongTermMemoryMapper {

    /**
     * 新增一条记忆，并把自增主键回填到入参对象上。
     * <p>
     * 主键必须回填：它是记忆的对外身份，管理页的删除、恢复和步骤 7 的写入决策都靠它。
     * <p>
     * 向量随插入一起写：写入时算一次 embedding 是设计文档 D4 的要求，分两条语句写会让
     * 「插入成功、向量失败」多一个中间态。旧列 {@code embedding_id} 是 Milvus 方案残留，
     * 已确定不再使用（改为内存余弦），因此不再写入。
     */
    @Insert("""
            INSERT INTO t_long_term_memory (user_id, memory_key, content, importance, source_session_id, embedding)
            VALUES (#{userId}, #{memoryKey}, #{content}, #{importance}, #{sourceSessionId}, #{embedding})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LongTermMemoryRecord record);

    @Select("""
            SELECT id, user_id, memory_key, content, importance, source_session_id, embedding_id,
                   last_accessed_time, create_time, update_time
            FROM t_long_term_memory
            WHERE id = #{id}
              AND user_id = #{userId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "LongTermMemoryRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "memory_key", property = "memoryKey"),
            @Result(column = "content", property = "content"),
            @Result(column = "importance", property = "importance"),
            @Result(column = "source_session_id", property = "sourceSessionId"),
            @Result(column = "embedding_id", property = "embeddingId"),
            @Result(column = "last_accessed_time", property = "lastAccessedTime"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<LongTermMemoryRecord> findActiveById(@Param("userId") String userId, @Param("id") Long id);

    /**
     * 管理页用的全量列表：不带向量列（1024 维的 JSON 约 20KB，一页几十条就会多传几 MB），
     * 也不做 limit——管理页需要能浏览、搜索全部记忆。
     */
    @Select("""
            SELECT id, user_id, memory_key, content, importance, source_session_id, embedding_id,
                   last_accessed_time, create_time, update_time
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
            @Result(column = "importance", property = "importance"),
            @Result(column = "source_session_id", property = "sourceSessionId"),
            @Result(column = "embedding_id", property = "embeddingId"),
            @Result(column = "last_accessed_time", property = "lastAccessedTime"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    List<LongTermMemoryRecord> listByUser(@Param("userId") String userId);

    /**
     * 检索用的候选集：取该用户全部未删除记忆，由 Java 侧做三维打分后取 top-K（设计文档 D1）。
     * <p>
     * <b>刻意不写 ORDER BY / LIMIT</b>：原来的 {@code ORDER BY update_time DESC LIMIT 10} 让时效性维度
     * 在打分之前就已经被吃掉（D11 第 3 条），而按更新时间排序还会因为任意一条记忆被更新而整体重排，
     * 这正是在打断 KV cache 的那个查询。排序与截断都交给 {@link MemoryScorer}。
     * <p>
     * 向量列只有这条查询会取：它是相关性维度的输入，而单用户量级（几十到几百条）下全量取回来做内存余弦
     * 完全够用，不需要向量库（D5）。
     */
    @Select("""
            SELECT id, user_id, memory_key, content, importance, source_session_id,
                   last_accessed_time, embedding, create_time, update_time
            FROM t_long_term_memory
            WHERE user_id = #{userId}
              AND is_deleted = 0
            """)
    @Results(value = {
            @Result(column = "id", property = "id"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "memory_key", property = "memoryKey"),
            @Result(column = "content", property = "content"),
            @Result(column = "importance", property = "importance"),
            @Result(column = "source_session_id", property = "sourceSessionId"),
            @Result(column = "last_accessed_time", property = "lastAccessedTime"),
            @Result(column = "embedding", property = "embedding"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    List<LongTermMemoryRecord> listActiveForRetrieval(@Param("userId") String userId);

    /**
     * 写入或更新一条记忆的向量（设计文档 D4）。
     * <p>
     * {@code update_time = update_time} 是刻意写的：库表上 {@code update_time} 带
     * {@code ON UPDATE CURRENT_TIMESTAMP}，而显式赋值会让 MySQL 跳过自动更新。
     * 向量只是派生数据，补算向量不该被记成「这条记忆的更新时间变了」——管理页按 update_time 排序，
     * 否则一次补算就会把整页顺序打乱。
     */
    @Update("""
            UPDATE t_long_term_memory
            SET embedding = #{embedding},
                update_time = update_time
            WHERE id = #{id}
              AND user_id = #{userId}
              AND is_deleted = 0
            """)
    int updateEmbedding(@Param("userId") String userId, @Param("id") Long id, @Param("embedding") String embedding);

    /**
     * 检索取用后写回访问时间（设计文档 D6）。
     * <p>
     * 批量一条 SQL 更新本次入选的几条，而不是逐条 update：一次检索最多几条，但每轮都会发生。
     * 同样用 {@code update_time = update_time} 保住「更新时间」的原意——被取用不等于内容变了。
     */
    @Update("""
            <script>
            UPDATE t_long_term_memory
            SET last_accessed_time = #{accessedTime},
                update_time = update_time
            WHERE user_id = #{userId}
              AND id IN
              <foreach item="id" collection="ids" open="(" separator="," close=")">
                #{id}
              </foreach>
            </script>
            """)
    int markAccessed(@Param("userId") String userId, @Param("ids") List<Long> ids,
                     @Param("accessedTime") LocalDateTime accessedTime);

    /**
     * 阶段二决策里的 UPDATE：沿用原 id 覆盖内容与分数（设计文档 D8）。
     * <p>
     * 只更新内容与分数，不动 source_session_id、向量与访问时间：这是同一条记忆换了说法，不是新记忆。
     */
    @Update("""
            UPDATE t_long_term_memory
            SET content = #{content},
                importance = #{importance},
                update_time = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND user_id = #{userId}
              AND is_deleted = 0
            """)
    int updateContent(@Param("userId") String userId, @Param("id") Long id,
                      @Param("content") String content, @Param("importance") int importance);

    /**
     * 阶段二决策里的 DELETE：把记忆置为失效，并区分「被新事实取代」与「用户主动删除」（设计文档 D15）。
     * <p>
     * deleted_by_user 显式写 0：被取代的记忆不允许通过管理页的撤销入口复活，superseded_by 指向替代它的新记录。
     */
    @Update("""
            UPDATE t_long_term_memory
            SET is_deleted = 1,
                deleted_by_user = 0,
                superseded_by = #{supersededBy},
                update_time = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND user_id = #{userId}
              AND is_deleted = 0
            """)
    int markDeletedByDecision(@Param("userId") String userId, @Param("id") Long id,
                              @Param("supersededBy") Long supersededBy);

    /**
     * 用户主动删除：同时打失效位与「用户删除」标记。
     * <p>
     * 两个标记缺一不可：is_deleted 负责所有查询的可见性过滤，deleted_by_user 决定这条记忆能否被恢复。
     * 被新事实取代的记忆由步骤 7 写 superseded_by 并把 is_deleted 置 1，但不会带 deleted_by_user 标记。
     */
    @Update("""
            UPDATE t_long_term_memory
            SET is_deleted = 1,
                deleted_by_user = 1,
                update_time = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND user_id = #{userId}
              AND is_deleted = 0
            """)
    int softDelete(@Param("userId") String userId, @Param("id") Long id);

    /**
     * 撤销用户主动删除。
     * <p>
     * 只翻转删除标记，不碰内容、来源会话与向量标识；额外挡掉 superseded_by 非空的行，
     * 因为被新事实取代的记忆不该借恢复入口复活（设计文档 D15）。
     */
    @Update("""
            UPDATE t_long_term_memory
            SET is_deleted = 0,
                deleted_by_user = 0,
                update_time = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND user_id = #{userId}
              AND is_deleted = 1
              AND deleted_by_user = 1
              AND superseded_by IS NULL
            """)
    int restore(@Param("userId") String userId, @Param("id") Long id);
}
