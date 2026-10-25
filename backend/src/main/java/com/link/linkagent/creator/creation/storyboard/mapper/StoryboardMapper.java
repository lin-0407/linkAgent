package com.link.linkagent.creator.creation.storyboard.mapper;

import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import org.apache.ibatis.annotations.*;
import java.util.*;

@Mapper
public interface StoryboardMapper {
    @Select("SELECT * FROM creator_creation_brief WHERE task_id=#{task} AND owner_id=#{owner} AND brief_id=#{id}")
    Optional<Brief> brief(@Param("task") String task,@Param("owner") String owner,@Param("id") String id);
    @Select("SELECT * FROM creator_creation_brief WHERE task_id=#{task} AND owner_id=#{owner} AND idempotency_key=#{key}")
    Optional<Brief> briefByKey(@Param("task") String task,@Param("owner") String owner,@Param("key") String key);
    @Select("SELECT COALESCE(MAX(revision),0) FROM creator_creation_brief WHERE task_id=#{task} AND owner_id=#{owner}")
    int briefRevision(@Param("task") String task,@Param("owner") String owner);
    @Insert("INSERT INTO creator_creation_brief(brief_id,task_id,owner_id,revision,idempotency_key,request_hash,document_json,source_json) VALUES(#{briefId},#{taskId},#{ownerId},#{revision},#{idempotencyKey},#{requestHash},#{documentJson},#{sourceJson})")
    int insertBrief(Brief brief);
    @Select("SELECT * FROM creator_creation_brief WHERE task_id=#{task} AND owner_id=#{owner} ORDER BY revision DESC LIMIT #{limit} OFFSET #{offset}")
    List<Brief> briefs(@Param("task") String task,@Param("owner") String owner,@Param("offset") int offset,@Param("limit") int limit);

    @Select("SELECT * FROM creator_creation_storyboard WHERE task_id=#{task} AND owner_id=#{owner} AND storyboard_id=#{id}")
    Optional<Board> board(@Param("task") String task,@Param("owner") String owner,@Param("id") String id);
    @Select("SELECT * FROM creator_creation_storyboard WHERE task_id=#{task} AND owner_id=#{owner} AND idempotency_key=#{key}")
    Optional<Board> boardByKey(@Param("task") String task,@Param("owner") String owner,@Param("key") String key);
    @Select("SELECT COALESCE(MAX(revision),0) FROM creator_creation_storyboard WHERE task_id=#{task} AND owner_id=#{owner}")
    int boardRevision(@Param("task") String task,@Param("owner") String owner);
    @Insert("""
        INSERT INTO creator_creation_storyboard(storyboard_id,task_id,owner_id,revision,brief_id,parent_storyboard_id,
            run_id,idempotency_key,request_hash,document_hash,document_json,asset_snapshot_json)
        VALUES(#{storyboardId},#{taskId},#{ownerId},#{revision},#{briefId},#{parentStoryboardId},#{runId},#{idempotencyKey},#{requestHash},#{documentHash},#{documentJson},#{assetSnapshotJson})
        """)
    int insertBoard(Board board);
    @Select("SELECT * FROM creator_creation_storyboard WHERE task_id=#{task} AND owner_id=#{owner} ORDER BY revision DESC LIMIT #{limit} OFFSET #{offset}")
    List<Board> boards(@Param("task") String task,@Param("owner") String owner,@Param("offset") int offset,@Param("limit") int limit);
    @Insert("INSERT INTO creator_creation_storyboard_asset(storyboard_id,asset_id,task_id,owner_id) VALUES(#{board},#{asset},#{task},#{owner})")
    int reference(@Param("board") String board,@Param("asset") String asset,@Param("task") String task,@Param("owner") String owner);
    record Reference(String storyboardId,int revision,String briefId) { }
    @Select("SELECT s.storyboard_id,s.revision,s.brief_id FROM creator_creation_storyboard_asset a JOIN creator_creation_storyboard s ON s.storyboard_id=a.storyboard_id AND s.task_id=a.task_id AND s.owner_id=a.owner_id WHERE a.task_id=#{task} AND a.owner_id=#{owner} AND a.asset_id=#{asset} ORDER BY s.revision DESC LIMIT #{limit} OFFSET #{offset}")
    List<Reference> references(@Param("task") String task,@Param("owner") String owner,@Param("asset") String asset,@Param("offset") int offset,@Param("limit") int limit);

    @Insert("INSERT INTO creator_creation_storyboard_job(run_id,task_id,owner_id,brief_id,request_hash) VALUES(#{runId},#{taskId},#{ownerId},#{briefId},#{requestHash})")
    int insertJob(Job job);
    @Select("SELECT * FROM creator_creation_storyboard_job WHERE task_id=#{task} AND owner_id=#{owner} AND run_id=#{run}")
    Optional<Job> job(@Param("task") String task,@Param("owner") String owner,@Param("run") String run);
    @Select("""
        SELECT j.run_id,j.task_id,j.owner_id,j.brief_id,j.request_hash,j.storyboard_id
        FROM creator_creation_storyboard_job j JOIN creator_creation_run r ON r.run_id=j.run_id
        WHERE j.task_id=#{task} AND j.owner_id=#{owner} ORDER BY r.create_time DESC,r.run_id DESC LIMIT 1
        """)
    Optional<Job> latestJob(@Param("task") String task,@Param("owner") String owner);
    @Update("UPDATE creator_creation_storyboard_job SET storyboard_id=#{board} WHERE run_id=#{run} AND storyboard_id IS NULL")
    int result(@Param("run") String run,@Param("board") String board);
    @Select("""
        SELECT j.* FROM creator_creation_storyboard_job j JOIN creator_creation_run r ON r.run_id=j.run_id AND r.task_id=j.task_id AND r.owner_id=j.owner_id
        JOIN creator_creation_workspace w ON w.task_id=j.task_id AND w.owner_id=j.owner_id AND w.active_run_id=r.run_id AND w.is_deleted=0
        JOIN creator_task t ON t.task_id=j.task_id AND t.user_id=j.owner_id AND t.is_deleted=0
        WHERE r.kind='PREPARE' AND (r.status='QUEUED' OR (r.status IN ('RUNNING','CANCEL_REQUESTED') AND (r.lease_expires_at IS NULL OR r.lease_expires_at<=NOW(6))))
        ORDER BY r.create_time LIMIT 10
        """)
    List<Job> candidates();
    @Select("""
        SELECT JSON_OBJECT('publishingSessionId',s.session_id,'publishingResultId',g.suggestion_id)
        FROM creator_workflow_session s JOIN creator_suggestion g ON g.task_id=s.task_id AND g.session_id=s.session_id AND g.suggestion_id=s.confirmed_result_id AND g.is_deleted=0
        WHERE s.task_id=#{task} AND s.user_id=#{owner} AND s.stage='PRE_PUBLISH' AND s.status='CONFIRMED' AND s.is_deleted=0
        AND s.id=(SELECT MAX(x.id) FROM creator_workflow_session x WHERE x.task_id=s.task_id AND x.user_id=s.user_id AND x.stage='PRE_PUBLISH' AND x.is_deleted=0)
        AND g.id=(SELECT MAX(x.id) FROM creator_suggestion x WHERE x.task_id=s.task_id AND x.is_deleted=0)
        """)
    Optional<String> publishing(@Param("task") String task,@Param("owner") String owner);
}
