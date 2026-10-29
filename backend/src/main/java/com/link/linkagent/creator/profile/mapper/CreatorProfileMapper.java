package com.link.linkagent.creator.profile.mapper;

import com.link.linkagent.creator.profile.model.CreatorProfileRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 创作者画像访问层。
 * 每个用户只保留一条画像记录，upsert 保证重复初始化不产生冗余数据。
 */
@Mapper
public interface CreatorProfileMapper {

    @Select("""
            SELECT id,
                   creator_id,
                   style_tags,
                   tone_guide,
                   audience_view,
                   last_processed_event_id,
                   create_time,
                   update_time
            FROM creator_profile
            WHERE creator_id = #{creatorId}
              AND is_deleted = 0
            """)
    @Results(id = "CreatorProfileRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "creator_id", property = "creatorId"),
            @Result(column = "style_tags", property = "styleTags"),
            @Result(column = "tone_guide", property = "toneGuide"),
            @Result(column = "audience_view", property = "audienceView"),
            @Result(column = "last_processed_event_id", property = "lastProcessedEventId"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    CreatorProfileRecord findByCreatorId(@Param("creatorId") String creatorId);

    /**
     * 插入新画像。creator_id 有唯一约束，重复插入会被数据库拒绝。
     */
    @Insert("""
            INSERT INTO creator_profile (
                creator_id,
                style_tags,
                tone_guide,
                audience_view
            )
            VALUES (
                #{creatorId},
                #{styleTags},
                #{toneGuide},
                #{audienceView}
            )
            """)
    int insert(CreatorProfileRecord record);

    /**
     * 内容和事件水位一次提交；令牌过期后迟到的旧模型结果不能覆盖新领取者。
     */
    @Update("""
            UPDATE creator_profile
            SET style_tags = #{record.styleTags},
                tone_guide = #{record.toneGuide},
                audience_view = #{record.audienceView},
                last_processed_event_id = #{record.lastProcessedEventId},
                refresh_token = NULL,
                refresh_started_at = NULL,
                update_time = CURRENT_TIMESTAMP
            WHERE creator_id = #{record.creatorId}
              AND refresh_token = #{token}
              AND is_deleted = 0
            """)
    int update(@Param("record") CreatorProfileRecord record, @Param("token") String token);

    /** 领取时校验水位，阻止并发检查重复调用模型；十分钟后可接替已退出进程的领取。 */
    @Update("""
            UPDATE creator_profile
            SET refresh_token = #{token}, refresh_started_at = CURRENT_TIMESTAMP
            WHERE creator_id = #{creatorId} AND is_deleted = 0
              AND last_processed_event_id = #{afterId}
              AND (refresh_token IS NULL OR refresh_started_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 10 MINUTE))
            """)
    int tryClaimRefresh(@Param("creatorId") String creatorId, @Param("afterId") long afterId,
                        @Param("token") String token);

    @Update("""
            UPDATE creator_profile
            SET refresh_token = NULL, refresh_started_at = NULL
            WHERE creator_id = #{creatorId} AND refresh_token = #{token}
            """)
    int releaseRefresh(@Param("creatorId") String creatorId, @Param("token") String token);
}
