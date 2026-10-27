package com.link.linkagent.creator.creation.draft;

import com.link.linkagent.creator.creation.draft.DraftModels.Draft;
import org.apache.ibatis.annotations.*;
import java.util.*;

@Mapper
public interface DraftMapper {
    String COLUMNS="draft_id,task_id,owner_id,version,base_draft_id,request_json,request_hash,idempotency_key,status,brief_id,preparation_run_id,storyboard_id,execution_id,expected_workspace_revision,authorization_limit_cny,error_message,dispatch_failures,create_time";
    @Select("SELECT "+COLUMNS+" FROM creator_creation_draft WHERE task_id=#{task} AND owner_id=#{owner} ORDER BY version DESC LIMIT 50")
    List<Draft> list(@Param("task")String task,@Param("owner")String owner);
    @Select("SELECT "+COLUMNS+" FROM creator_creation_draft WHERE task_id=#{task} AND owner_id=#{owner} AND draft_id=#{id}")
    Optional<Draft> find(@Param("task")String task,@Param("owner")String owner,@Param("id")String id);
    @Select("SELECT "+COLUMNS+" FROM creator_creation_draft WHERE task_id=#{task} AND owner_id=#{owner} AND idempotency_key=#{key}")
    Optional<Draft> byKey(@Param("task")String task,@Param("owner")String owner,@Param("key")String key);
    @Select("SELECT d."+"draft_id,d.task_id,d.owner_id,d.version,d.base_draft_id,d.request_json,d.request_hash,d.idempotency_key,d.status,d.brief_id,d.preparation_run_id,d.storyboard_id,d.execution_id,d.expected_workspace_revision,d.authorization_limit_cny,d.error_message,d.dispatch_failures,d.create_time FROM creator_creation_draft d JOIN creator_task t ON t.task_id=d.task_id AND t.is_deleted=0 WHERE d.status IN ('PREPARING','EXECUTING','CANCEL_REQUESTED') ORDER BY d.update_time LIMIT 20")
    List<Draft> candidates();
    @Select("SELECT COUNT(*) FROM creator_creation_asset WHERE task_id=#{task} AND owner_id=#{owner} AND asset_id=#{asset} AND status='READY' AND cleanup_pending=0")
    int readyAsset(@Param("task")String task,@Param("owner")String owner,@Param("asset")String asset);
    @Insert("""
        INSERT INTO creator_creation_draft(draft_id,task_id,owner_id,version,base_draft_id,request_json,request_hash,
            idempotency_key,status,brief_id,preparation_run_id,expected_workspace_revision,authorization_limit_cny)
        VALUES(#{draftId},#{taskId},#{ownerId},#{version},#{baseDraftId},#{requestJson},#{requestHash},#{idempotencyKey},
            #{status},#{briefId},#{preparationRunId},#{expectedWorkspaceRevision},#{authorizationLimitCny})
        """)
    int insert(Draft draft);
    @Update("""
        UPDATE creator_creation_draft SET status=#{status},storyboard_id=#{board},execution_id=#{execution},
            error_message=#{message},dispatch_failures=0 WHERE draft_id=#{id}
        """)
    int update(@Param("id")String id,@Param("status")String status,@Param("board")String board,
            @Param("execution")String execution,@Param("message")String message);
    @Update("""
        UPDATE creator_creation_draft SET dispatch_failures=dispatch_failures+1,
            status=IF(dispatch_failures>=3,'WAITING_USER',status),
            error_message='本地制作恢复失败，已保留进度。请查看后端日志，结束本次制作后重试。'
        WHERE draft_id=#{id} AND status IN ('PREPARING','EXECUTING','CANCEL_REQUESTED')
        """)
    int dispatchFailed(@Param("id")String id);

    @Select("""
        SELECT COALESCE(SUM(IF(status='SETTLED',known_cost_cny,0)),0) known_cny,
            COALESCE(SUM(IF(status='RESERVED',reserved_cny,0)),0) reserved_cny,
            COALESCE(SUM(IF(status='UNKNOWN',reserved_cny,0)),0) unknown_cny,
            COALESCE(SUM(IF(status='SETTLED' AND known_cost_cny>reserved_cny,1,0)),0) overrun_count
        FROM creator_creation_cost_entry WHERE task_id=#{task} AND owner_id=#{owner} AND run_id=#{run}
        """)
    com.link.linkagent.creator.creation.model.CreationRuntimeModels.Totals preparationTotals(
            @Param("task")String task,@Param("owner")String owner,@Param("run")String run);
}
