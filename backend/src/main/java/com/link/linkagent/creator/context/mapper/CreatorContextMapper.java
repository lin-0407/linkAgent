package com.link.linkagent.creator.context.mapper;

import com.link.linkagent.creator.context.model.CreatorContextTermRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * 创作者语境库访问层。
 * 继续使用注解 SQL，是为了和当前 creator 模块保持一致，后续检索 SQL 变复杂时再迁移 XML。
 */
@Mapper
public interface CreatorContextMapper {

    @Insert("""
            INSERT INTO creator_context_term (
                term_id,
                user_id,
                video_type,
                term,
                normalized_term,
                term_type,
                polarity,
                source_type,
                source_task_id,
                evidence_text,
                weight,
                enabled
            )
            VALUES (
                #{termId},
                #{userId},
                #{videoType},
                #{term},
                #{normalizedTerm},
                #{termType},
                #{polarity},
                #{sourceType},
                #{sourceTaskId},
                #{evidenceText},
                #{weight},
                #{enabled}
            )
            ON DUPLICATE KEY UPDATE
                term = VALUES(term),
                polarity = VALUES(polarity),
                source_type = VALUES(source_type),
                source_task_id = COALESCE(VALUES(source_task_id), source_task_id),
                evidence_text = COALESCE(VALUES(evidence_text), evidence_text),
                weight = LEAST(100, GREATEST(weight, VALUES(weight)) + 1),
                enabled = 1,
                is_deleted = 0,
                update_time = CURRENT_TIMESTAMP
            """)
    int upsertTerm(CreatorContextTermRecord record);

    @Select("""
            SELECT id,
                   term_id,
                   user_id,
                   video_type,
                   term,
                   normalized_term,
                   term_type,
                   polarity,
                   source_type,
                   source_task_id,
                   evidence_text,
                   weight,
                   usage_count,
                   accept_count,
                   reject_count,
                   enabled,
                   create_time,
                   update_time
            FROM creator_context_term
            WHERE user_id = #{userId}
              AND video_type = #{videoType}
              AND normalized_term = #{normalizedTerm}
              AND term_type = #{termType}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "CreatorContextTermRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "term_id", property = "termId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "video_type", property = "videoType"),
            @Result(column = "term", property = "term"),
            @Result(column = "normalized_term", property = "normalizedTerm"),
            @Result(column = "term_type", property = "termType"),
            @Result(column = "polarity", property = "polarity"),
            @Result(column = "source_type", property = "sourceType"),
            @Result(column = "source_task_id", property = "sourceTaskId"),
            @Result(column = "evidence_text", property = "evidenceText"),
            @Result(column = "weight", property = "weight"),
            @Result(column = "usage_count", property = "usageCount"),
            @Result(column = "accept_count", property = "acceptCount"),
            @Result(column = "reject_count", property = "rejectCount"),
            @Result(column = "enabled", property = "enabled"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<CreatorContextTermRecord> findByIdentity(@Param("userId") String userId,
                                                      @Param("videoType") String videoType,
                                                      @Param("normalizedTerm") String normalizedTerm,
                                                      @Param("termType") String termType);

    @Select("""
            SELECT id,
                   term_id,
                   user_id,
                   video_type,
                   term,
                   normalized_term,
                   term_type,
                   polarity,
                   source_type,
                   source_task_id,
                   evidence_text,
                   weight,
                   usage_count,
                   accept_count,
                   reject_count,
                   enabled,
                   create_time,
                   update_time
            FROM creator_context_term
            WHERE term_id = #{termId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @ResultMap("CreatorContextTermRecordMap")
    Optional<CreatorContextTermRecord> findByTermId(@Param("termId") String termId);

    @Select("""
            SELECT id,
                   term_id,
                   user_id,
                   video_type,
                   term,
                   normalized_term,
                   term_type,
                   polarity,
                   source_type,
                   source_task_id,
                   evidence_text,
                   weight,
                   usage_count,
                   accept_count,
                   reject_count,
                   enabled,
                   create_time,
                   update_time
            FROM creator_context_term
            WHERE user_id = #{userId}
              AND is_deleted = 0
              AND (#{includeDisabled} = TRUE OR enabled = 1)
              AND (#{videoType} IS NULL OR video_type = #{videoType})
              AND (#{termType} IS NULL OR term_type = #{termType})
            ORDER BY enabled DESC, weight DESC, update_time DESC, id DESC
            LIMIT #{limit}
            """)
    @ResultMap("CreatorContextTermRecordMap")
    List<CreatorContextTermRecord> listTerms(@Param("userId") String userId,
                                             @Param("videoType") String videoType,
                                             @Param("termType") String termType,
                                             @Param("includeDisabled") boolean includeDisabled,
                                             @Param("limit") int limit);

    @Select("""
            SELECT id,
                   term_id,
                   user_id,
                   video_type,
                   term,
                   normalized_term,
                   term_type,
                   polarity,
                   source_type,
                   source_task_id,
                   evidence_text,
                   weight,
                   usage_count,
                   accept_count,
                   reject_count,
                   enabled,
                   create_time,
                   update_time
            FROM creator_context_term
            WHERE user_id = #{userId}
              AND is_deleted = 0
              AND enabled = 1
              AND (video_type = #{videoType} OR video_type = #{globalVideoType})
            ORDER BY CASE WHEN video_type = #{videoType} THEN 0 ELSE 1 END,
                     weight DESC,
                     update_time DESC,
                     id DESC
            LIMIT #{limit}
            """)
    @ResultMap("CreatorContextTermRecordMap")
    List<CreatorContextTermRecord> listForPrompt(@Param("userId") String userId,
                                                 @Param("videoType") String videoType,
                                                 @Param("globalVideoType") String globalVideoType,
                                                 @Param("limit") int limit);

    @Update("""
            UPDATE creator_context_term
            SET enabled = 0,
                update_time = CURRENT_TIMESTAMP
            WHERE term_id = #{termId}
              AND is_deleted = 0
            """)
    int disableTerm(@Param("termId") String termId);

    @Update("""
            UPDATE creator_context_term
            SET usage_count = usage_count + 1,
                accept_count = accept_count + 1,
                weight = LEAST(100, weight + 3),
                enabled = 1,
                update_time = CURRENT_TIMESTAMP
            WHERE term_id = #{termId}
              AND is_deleted = 0
            """)
    int acceptTerm(@Param("termId") String termId);

    @Update("""
            UPDATE creator_context_term
            SET usage_count = usage_count + 1,
                reject_count = reject_count + 1,
                weight = GREATEST(0, weight - 4),
                enabled = IF(GREATEST(0, weight - 4) <= 4, 0, enabled),
                update_time = CURRENT_TIMESTAMP
            WHERE term_id = #{termId}
              AND is_deleted = 0
            """)
    int rejectTerm(@Param("termId") String termId);
}
