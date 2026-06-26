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
     * 更新画像内容。
     * 只更新三个核心字段，update_time 由数据库 ON UPDATE CURRENT_TIMESTAMP 自动刷新。
     */
    @Update("""
            UPDATE creator_profile
            SET style_tags   = #{styleTags},
                tone_guide   = #{toneGuide},
                audience_view = #{audienceView}
            WHERE creator_id = #{creatorId}
              AND is_deleted = 0
            """)
    int update(CreatorProfileRecord record);
}
