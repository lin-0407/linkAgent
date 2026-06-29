package com.link.linkagent.creator.interactive.mapper;

import com.link.linkagent.creator.interactive.model.CreativeIdeaOptionRecord;
import com.link.linkagent.creator.interactive.model.InteractiveSessionRecord;
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
 * 交互式创作访问层。
 * 创意会话和卡片先保持独立表，避免破坏已有创作任务、发布前优化、复盘链路。
 */
@Mapper
public interface CreatorInteractiveMapper {

    @Insert("""
            INSERT INTO creator_interactive_session (
                session_id,
                task_id,
                user_id,
                idea,
                video_type,
                status,
                selected_option_id,
                raw_output,
                parse_status
            )
            VALUES (
                #{sessionId},
                #{taskId},
                #{userId},
                #{idea},
                #{videoType},
                #{status},
                #{selectedOptionId},
                #{rawOutput},
                #{parseStatus}
            )
            """)
    int insertSession(InteractiveSessionRecord record);

    @Update("""
            UPDATE creator_interactive_session
            SET status = #{status},
                raw_output = #{rawOutput},
                parse_status = #{parseStatus},
                update_time = CURRENT_TIMESTAMP
            WHERE session_id = #{sessionId}
              AND is_deleted = 0
            """)
    int updateSessionGenerationResult(@Param("sessionId") String sessionId,
                                      @Param("status") String status,
                                      @Param("rawOutput") String rawOutput,
                                      @Param("parseStatus") String parseStatus);

    @Update("""
            UPDATE creator_interactive_session
            SET status = #{status},
                selected_option_id = #{selectedOptionId},
                update_time = CURRENT_TIMESTAMP
            WHERE session_id = #{sessionId}
              AND is_deleted = 0
            """)
    int updateSessionSelection(@Param("sessionId") String sessionId,
                               @Param("status") String status,
                               @Param("selectedOptionId") String selectedOptionId);

    @Select("""
            SELECT id,
                   session_id,
                   task_id,
                   user_id,
                   idea,
                   video_type,
                   status,
                   selected_option_id,
                   raw_output,
                   parse_status,
                   create_time,
                   update_time
            FROM creator_interactive_session
            WHERE task_id = #{taskId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @Results(id = "InteractiveSessionRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "idea", property = "idea"),
            @Result(column = "video_type", property = "videoType"),
            @Result(column = "status", property = "status"),
            @Result(column = "selected_option_id", property = "selectedOptionId"),
            @Result(column = "raw_output", property = "rawOutput"),
            @Result(column = "parse_status", property = "parseStatus"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    Optional<InteractiveSessionRecord> findSessionByTaskId(@Param("taskId") String taskId);

    @Insert("""
            INSERT INTO creator_idea_option (
                option_id,
                session_id,
                task_id,
                option_name,
                target_audience,
                title_outline,
                content_outline,
                description_outline,
                selling_points,
                risk_points,
                recommend_reason,
                selected
            )
            VALUES (
                #{optionId},
                #{sessionId},
                #{taskId},
                #{optionName},
                #{targetAudience},
                #{titleOutline},
                #{contentOutline},
                #{descriptionOutline},
                #{sellingPoints},
                #{riskPoints},
                #{recommendReason},
                #{selected}
            )
            """)
    int insertOption(CreativeIdeaOptionRecord record);

    @Update("""
            UPDATE creator_idea_option
            SET is_deleted = 1,
                selected = 0,
                update_time = CURRENT_TIMESTAMP
            WHERE session_id = #{sessionId}
              AND is_deleted = 0
            """)
    int deleteOptionsBySessionId(@Param("sessionId") String sessionId);

    @Select("""
            SELECT id,
                   option_id,
                   session_id,
                   task_id,
                   option_name,
                   target_audience,
                   title_outline,
                   content_outline,
                   description_outline,
                   selling_points,
                   risk_points,
                   recommend_reason,
                   selected,
                   create_time,
                   update_time
            FROM creator_idea_option
            WHERE session_id = #{sessionId}
              AND is_deleted = 0
            ORDER BY id ASC
            """)
    @Results(id = "CreativeIdeaOptionRecordMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "option_id", property = "optionId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "task_id", property = "taskId"),
            @Result(column = "option_name", property = "optionName"),
            @Result(column = "target_audience", property = "targetAudience"),
            @Result(column = "title_outline", property = "titleOutline"),
            @Result(column = "content_outline", property = "contentOutline"),
            @Result(column = "description_outline", property = "descriptionOutline"),
            @Result(column = "selling_points", property = "sellingPoints"),
            @Result(column = "risk_points", property = "riskPoints"),
            @Result(column = "recommend_reason", property = "recommendReason"),
            @Result(column = "selected", property = "selected"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    List<CreativeIdeaOptionRecord> listOptionsBySessionId(@Param("sessionId") String sessionId);

    @Select("""
            SELECT id,
                   option_id,
                   session_id,
                   task_id,
                   option_name,
                   target_audience,
                   title_outline,
                   content_outline,
                   description_outline,
                   selling_points,
                   risk_points,
                   recommend_reason,
                   selected,
                   create_time,
                   update_time
            FROM creator_idea_option
            WHERE task_id = #{taskId}
              AND option_id = #{optionId}
              AND is_deleted = 0
            LIMIT 1
            """)
    @ResultMap("CreativeIdeaOptionRecordMap")
    Optional<CreativeIdeaOptionRecord> findOptionByTaskIdAndOptionId(@Param("taskId") String taskId,
                                                                     @Param("optionId") String optionId);

    @Update("""
            UPDATE creator_idea_option
            SET selected = 0,
                update_time = CURRENT_TIMESTAMP
            WHERE session_id = #{sessionId}
              AND is_deleted = 0
            """)
    int clearSelectedOptions(@Param("sessionId") String sessionId);

    @Update("""
            UPDATE creator_idea_option
            SET selected = 1,
                update_time = CURRENT_TIMESTAMP
            WHERE option_id = #{optionId}
              AND session_id = #{sessionId}
              AND is_deleted = 0
            """)
    int selectOption(@Param("sessionId") String sessionId, @Param("optionId") String optionId);
}
