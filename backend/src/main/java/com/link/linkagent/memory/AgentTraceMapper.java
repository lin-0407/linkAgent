package com.link.linkagent.memory;

import com.link.linkagent.memory.model.AgentStepRecord;
import com.link.linkagent.memory.model.AgentTraceRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Agent 执行链路 MySQL 访问层。
 * <p>
 * 这里采用注解 SQL，是因为 P0 只需要少量明确的插入、更新和回查能力，暂不引入 XML 增加维护成本。
 */
@Mapper
public interface AgentTraceMapper {

    @Insert("""
            INSERT INTO t_agent_trace (
                trace_id, session_id, user_input, final_output, status,
                total_tokens, total_steps, start_time, end_time, error_msg, create_time
            )
            VALUES (
                #{traceId}, #{sessionId}, #{userInput}, #{finalOutput}, #{status},
                #{totalTokens}, #{totalSteps}, #{startTime}, #{endTime}, #{errorMsg}, #{createTime}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTrace(AgentTraceRecord trace);

    @Update("""
            UPDATE t_agent_trace
            SET final_output = #{finalOutput},
                status = #{status},
                total_tokens = #{totalTokens},
                total_steps = #{totalSteps},
                end_time = #{endTime},
                error_msg = #{errorMsg}
            WHERE trace_id = #{traceId}
              AND is_deleted = 0
            """)
    int completeTrace(AgentTraceRecord trace);

    @Insert("""
            INSERT INTO t_agent_step (
                trace_id, step_index, step_type, content,
                tool_name, tool_input, tool_output, token_count, create_time
            )
            VALUES (
                #{traceId}, #{stepIndex}, #{stepType}, #{content},
                #{toolName}, #{toolInput}, #{toolOutput}, #{tokenCount}, #{createTime}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertStep(AgentStepRecord step);

    @Select("""
            SELECT id, trace_id, session_id, user_input, final_output, status,
                   total_tokens, total_steps, start_time, end_time, error_msg, create_time, is_deleted
            FROM t_agent_trace
            WHERE session_id = #{sessionId}
              AND is_deleted = 0
            ORDER BY create_time DESC
            LIMIT #{limit}
            """)
    List<AgentTraceRecord> listTracesBySession(@Param("sessionId") String sessionId,
                                               @Param("limit") int limit);

    @Select("""
            SELECT id, trace_id, step_index, step_type, content, tool_name,
                   tool_input, tool_output, token_count, create_time, is_deleted
            FROM t_agent_step
            WHERE trace_id = #{traceId}
              AND is_deleted = 0
            ORDER BY step_index ASC, id ASC
            """)
    List<AgentStepRecord> listStepsByTrace(@Param("traceId") String traceId);
}
