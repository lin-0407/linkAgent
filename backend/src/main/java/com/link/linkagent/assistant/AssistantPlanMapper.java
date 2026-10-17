package com.link.linkagent.assistant;

import com.link.linkagent.assistant.AssistantPlanModels.Row;
import org.apache.ibatis.annotations.*;
import java.util.*;

/** 以版本号抢占整个计划；每步调用前后分别落库，不跨模型调用持有事务。 */
@Mapper
public interface AssistantPlanMapper {
    @Insert("""
        INSERT INTO creator_assistant_plan(plan_id,task_id,title,status,definition_json,state_json,revision,digest,create_time)
        VALUES(#{planId},#{taskId},#{title},#{status},#{definitionJson},#{stateJson},#{revision},#{digest},#{createTime})
        """)
    int insert(Row row);
    @Select("SELECT * FROM creator_assistant_plan WHERE plan_id=#{id}")
    Optional<Row> find(String id);
    @Select("""
        SELECT * FROM creator_assistant_plan ORDER BY create_time DESC,plan_id DESC LIMIT 20
        """)
    List<Row> recent();
    @Select("SELECT * FROM creator_assistant_plan WHERE status='WAITING' ORDER BY create_time LIMIT 20")
    List<Row> waiting();
    @Update("""
        UPDATE creator_assistant_plan SET status=#{status},state_json=#{state},revision=revision+1
        WHERE plan_id=#{id} AND revision=#{revision}
        """)
    int update(@Param("id") String id,@Param("revision") int revision,@Param("status") String status,@Param("state") String state);
    @Update("""
        UPDATE creator_assistant_plan SET status='INTERRUPTED',revision=revision+1 WHERE status='RUNNING'
        """)
    int interruptPreviousRuns();
}
