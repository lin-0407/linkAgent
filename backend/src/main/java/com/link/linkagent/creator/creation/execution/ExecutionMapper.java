package com.link.linkagent.creator.creation.execution;

import org.apache.ibatis.annotations.*;
import java.util.*;
import com.link.linkagent.creator.creation.execution.ExecutionModels.*;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Totals;

@Mapper
public interface ExecutionMapper {
    String COLUMNS="execution_id,task_id,owner_id,kind,status,stage,source_storyboard_id,storyboard_id,scene_ids_json,publishing_session_id,publishing_result_id,base_version_id,max_additional_cost_cny,current_run_id,expected_workspace_revision,idempotency_key,request_hash,provider_fingerprint,error_code,objects_json,create_time";
    @Select("SELECT "+COLUMNS+" FROM creator_creation_execution WHERE task_id=#{task} AND owner_id=#{owner} AND execution_id=#{id}")
    Optional<Execution> find(@Param("task")String task,@Param("owner")String owner,@Param("id")String id);
    @Select("SELECT "+COLUMNS+" FROM creator_creation_execution WHERE task_id=#{task} AND owner_id=#{owner} AND idempotency_key=#{key}")
    Optional<Execution> byKey(@Param("task")String task,@Param("owner")String owner,@Param("key")String key);
    @Select("SELECT "+COLUMNS+" FROM creator_creation_execution WHERE task_id=#{task} AND owner_id=#{owner} ORDER BY create_time DESC,execution_id DESC LIMIT 50")
    List<Execution> list(@Param("task")String task,@Param("owner")String owner);
    @Select("SELECT "+COLUMNS+" FROM creator_creation_execution WHERE status IN ('ACTIVE','CANCEL_REQUESTED') ORDER BY update_time LIMIT 20")
    List<Execution> candidates();
    @Insert("""
        INSERT INTO creator_creation_execution(execution_id,task_id,owner_id,kind,status,stage,source_storyboard_id,storyboard_id,
            scene_ids_json,publishing_session_id,publishing_result_id,base_version_id,max_additional_cost_cny,
            expected_workspace_revision,idempotency_key,request_hash,provider_fingerprint,objects_json)
        VALUES(#{executionId},#{taskId},#{ownerId},#{kind},#{status},#{stage},#{sourceStoryboardId},#{storyboardId},
            #{sceneIdsJson},#{publishingSessionId},#{publishingResultId},#{baseVersionId},#{maxAdditionalCostCny},
            #{expectedWorkspaceRevision},#{idempotencyKey},#{requestHash},#{providerFingerprint},'[]')
        """)
    int insert(Execution execution);
    @Update("""
        UPDATE creator_creation_execution SET status=#{status},stage=#{stage},storyboard_id=#{board},current_run_id=#{run},
            expected_workspace_revision=#{revision},error_code=#{error},dispatch_failures=0
        WHERE execution_id=#{id} AND status IN ('ACTIVE','WAITING_USER','CANCEL_REQUESTED')
        """)
    int update(@Param("id")String id,@Param("status")String status,@Param("stage")String stage,@Param("board")String board,
            @Param("run")String run,@Param("revision")long revision,@Param("error")String error);
    @Update("""
        UPDATE creator_creation_execution SET status=IF(dispatch_failures>=2,'WAITING_USER',status),
            error_code=IF(dispatch_failures>=2,'DISPATCH_FAILED',error_code),dispatch_failures=dispatch_failures+1
        WHERE execution_id=#{id} AND status='ACTIVE'
        """)
    int dispatchFailed(@Param("id")String id);
    @Insert("INSERT INTO creator_creation_execution_run(execution_id,run_id,stage) VALUES(#{id},#{run},#{stage})")
    int link(@Param("id")String id,@Param("run")String run,@Param("stage")String stage);
    @Select("""
        SELECT COALESCE(SUM(IF(c.status='SETTLED',c.known_cost_cny,0)),0) known_cny,
            COALESCE(SUM(IF(c.status='RESERVED',c.reserved_cny,0)),0) reserved_cny,
            COALESCE(SUM(IF(c.status='UNKNOWN',c.reserved_cny,0)),0) unknown_cny,
            COALESCE(SUM(IF(c.status='SETTLED' AND c.known_cost_cny>c.reserved_cny,1,0)),0) overrun_count
        FROM creator_creation_execution_run x JOIN creator_creation_cost_entry c ON c.run_id=x.run_id WHERE x.execution_id=#{id}
        """)
    Totals costs(@Param("id")String id);
    @Insert("""
        INSERT INTO creator_creation_execution_object(execution_id,generation,file_name,bucket_name,object_key,bytes,sha256,content_type)
        VALUES(#{executionId},#{generation},#{fileName},#{bucketName},#{objectKey},#{bytes},#{sha256},#{contentType})
        """)
    int plan(Output output);
    @Update("UPDATE creator_creation_execution_object SET adopted=1 WHERE execution_id=#{id} AND generation=#{generation}")
    int adopt(@Param("id")String id,@Param("generation")long generation);
    @Select("SELECT execution_id,generation,file_name,bucket_name,object_key,bytes,sha256,content_type,adopted,deleted FROM creator_creation_execution_object WHERE execution_id=#{id} AND adopted=1 AND deleted=0")
    List<Output> outputs(@Param("id")String id);
    @Select("SELECT execution_id,generation,file_name,bucket_name,object_key,bytes,sha256,content_type,adopted,deleted FROM creator_creation_execution_object WHERE execution_id=#{id} AND generation=#{generation} AND deleted=0")
    List<Output> generationOutputs(@Param("id")String id,@Param("generation")long generation);
    @Select("""
        SELECT o.execution_id,o.generation,o.file_name,o.bucket_name,o.object_key,o.bytes,o.sha256,o.content_type,o.adopted,o.deleted
        FROM creator_creation_execution_object o JOIN creator_creation_execution e ON e.execution_id=o.execution_id
        WHERE o.adopted=0 AND o.deleted=0 AND e.status IN ('COMPLETED','CANCELLED') LIMIT 30
        """)
    List<Output> garbage();
    @Update("UPDATE creator_creation_execution_object SET deleted=1 WHERE execution_id=#{executionId} AND generation=#{generation} AND file_name=#{fileName} AND adopted=0")
    int deleted(Output output);
}
