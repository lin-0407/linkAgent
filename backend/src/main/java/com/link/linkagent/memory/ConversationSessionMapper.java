package com.link.linkagent.memory;

import com.link.linkagent.memory.model.ConversationMessageRecord;
import com.link.linkagent.memory.model.ConversationSessionRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * 会话与消息 MySQL 访问层。
 * <p>
 * MySQL 会话历史是 Redis 短期记忆的长期副本，因此写入失败不应由 Mapper 决定是否中断，调用方会做旁路保护。
 */
@Mapper
public interface ConversationSessionMapper {

    @Insert("""
            INSERT INTO t_conversation_session (
                session_id, user_id, title, status, create_time, update_time
            )
            VALUES (
                #{sessionId}, #{userId}, #{title}, #{status}, #{createTime}, #{updateTime}
            )
            ON DUPLICATE KEY UPDATE
                user_id = VALUES(user_id),
                title = IF(title = '', VALUES(title), title),
                status = VALUES(status),
                update_time = VALUES(update_time),
                is_deleted = 0
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsertSession(ConversationSessionRecord session);

    @Insert("""
            INSERT INTO t_conversation_message (
                session_id, user_id, role, content, tool_name, token_count, create_time
            )
            VALUES (
                #{sessionId}, #{userId}, #{role}, #{content}, #{toolName}, #{tokenCount}, #{createTime}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertMessage(ConversationMessageRecord message);

    @Select("""
            SELECT id, session_id, user_id, title, status, create_time, update_time, is_deleted
            FROM t_conversation_session
            WHERE user_id = #{userId}
              AND is_deleted = 0
            ORDER BY update_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<ConversationSessionRecord> listSessionsByUser(@Param("userId") String userId,
                                                       @Param("limit") int limit);

    @Select("""
            SELECT id, session_id, role, content, tool_name, token_count, create_time, is_deleted
            FROM t_conversation_message
            WHERE session_id = #{sessionId}
              AND is_deleted = 0
            ORDER BY create_time ASC, id ASC
            """)
    List<ConversationMessageRecord> listMessagesBySession(@Param("sessionId") String sessionId);

    /**
     * 按用户捞取长期记忆游标之后的增量消息，只取最新的一批（设计文档 C2 / C4 / 5.3）。
     *
     * <h3>为什么倒序取</h3>
     * 游标为空（首次使用）时，正序 LIMIT 会把最老的几百条历史消息当成增量，把它们抽进长期记忆；
     * 倒序取最近一批再让调用方翻正，语义就是「只抽最近的一段对话」，历史不补抽。
     *
     * <h3>为什么不 JOIN 会话表</h3>
     * 消息表自带 user_id（设计文档 C5），增量查询直接命中 idx_user_id_id，省掉 JOIN 与去重；
     * 顺带也不会再因为会话被删（s.is_deleted=1）而漏掉该会话里尚未抽取的消息。
     *
     * @param afterId 游标（已抽取到的最大消息 id），只返回 id 大于它的消息
     * @param limit   单批最多取多少条
     * @return 按 id 倒序（新的在前）的消息记录；调用方需要按时间顺序使用时自行翻正
     */
    @Select("""
            SELECT id, session_id, user_id, role, content, tool_name, token_count, create_time, is_deleted
            FROM t_conversation_message
            WHERE user_id = #{userId}
              AND id > #{afterId}
              AND role IN ('user', 'assistant')
              AND is_deleted = 0
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<ConversationMessageRecord> listLatestMessagesAfterIdForUser(@Param("userId") String userId,
                                                                     @Param("afterId") long afterId,
                                                                     @Param("limit") int limit);

    /**
     * 查询会话最近一条摘要检查点。
     * <p>
     * 摘要以 role='summary' 的消息形式持久化（设计文档 B5）：它是会话历史的一部分，不是后端内存里的黑盒。
     * 会话恢复时用它还原模型上下文，前端历史展示也用它。被压缩的原文仍在同表保留，只是不再进入模型上下文。
     */
    @Select("""
            SELECT id, session_id, role, content, tool_name, token_count, create_time, is_deleted
            FROM t_conversation_message
            WHERE session_id = #{sessionId}
              AND role = 'summary'
              AND is_deleted = 0
            ORDER BY id DESC
            LIMIT 1
            """)
    Optional<ConversationMessageRecord> findLatestSummary(@Param("sessionId") String sessionId);
}
