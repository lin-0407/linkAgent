package com.link.linkagent.creator.creation.aivideo.mapper;

import com.link.linkagent.creator.creation.aivideo.model.CreationVideo;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Optional;

/** AI 视频任务与场景事实；所有成功写入都带运行租约围栏，迟到的旧执行不能覆盖新代结果。 */
@Mapper
public interface CreationVideoMapper {

    String LEASE_FENCE = """
            AND EXISTS(SELECT 1 FROM creator_creation_run r WHERE r.run_id=v.run_id AND r.task_id=v.task_id
                AND r.owner_id=v.owner_id AND r.status='RUNNING' AND r.lease_owner=#{worker}
                AND r.lease_generation=#{generation} AND r.lease_expires_at>CURRENT_TIMESTAMP(6))
            """;

    @Insert("""
            INSERT INTO creator_creation_video_job(run_id,task_id,owner_id,brief_id,storyboard_id,source_scope,
                idempotency_key,request_hash)
            VALUES(#{runId},#{taskId},#{ownerId},#{briefId},#{storyboardId},#{scope},#{key},#{requestHash})
            """)
    int insertJob(@Param("runId") String runId, @Param("taskId") String taskId, @Param("ownerId") String ownerId,
                  @Param("briefId") String briefId, @Param("storyboardId") String storyboardId,
                  @Param("scope") String scope, @Param("key") String key, @Param("requestHash") String requestHash);

    @Select("SELECT run_id,task_id,owner_id,brief_id,storyboard_id,source_scope,request_hash,storyboard_result_id "
            + "FROM creator_creation_video_job WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND idempotency_key=#{key}")
    Optional<Job> jobByKey(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("key") String key);

    @Select("SELECT run_id,task_id,owner_id,brief_id,storyboard_id,source_scope,request_hash,storyboard_result_id "
            + "FROM creator_creation_video_job WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId}")
    Optional<Job> job(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId);

    @Update("UPDATE creator_creation_video_job SET storyboard_result_id=#{board} WHERE run_id=#{runId} AND storyboard_result_id IS NULL")
    int result(@Param("runId") String runId, @Param("board") String board);

    @Select("""
            SELECT j.run_id,j.task_id,j.owner_id,j.brief_id,j.storyboard_id,j.source_scope,j.request_hash,
                j.storyboard_result_id
            FROM creator_creation_video_job j
            JOIN creator_creation_run r ON r.run_id=j.run_id AND r.task_id=j.task_id AND r.owner_id=j.owner_id
            JOIN creator_creation_workspace w ON w.task_id=j.task_id AND w.owner_id=j.owner_id
                AND w.active_run_id=r.run_id AND w.is_deleted=0
            JOIN creator_task t ON t.task_id=j.task_id AND t.user_id=j.owner_id AND t.is_deleted=0
            WHERE r.kind='PREPARE' AND (r.status='QUEUED' OR (r.status IN ('RUNNING','CANCEL_REQUESTED')
                AND (r.lease_expires_at IS NULL OR r.lease_expires_at<=NOW(6))))
            ORDER BY r.create_time LIMIT 10
            """)
    List<Job> candidates();

    record Job(String runId, String taskId, String ownerId, String briefId, String storyboardId,
               String sourceScope, String requestHash, String storyboardResultId) { }

    @Insert("""
            INSERT INTO creator_creation_video(run_id,scene_id,task_id,owner_id,brief_id,storyboard_id,position,status,
                prompt_text,prompt_sha256,scene_duration_ms,requested_seconds,video_asset_id,video_duration_ms)
            VALUES(#{runId},#{sceneId},#{taskId},#{ownerId},#{briefId},#{storyboardId},#{position},#{status},
                #{promptText},#{promptSha256},#{sceneDurationMs},#{requestedSeconds},#{videoAssetId},#{videoDurationMs})
            """)
    int insert(CreationVideo video);

    @Select("SELECT * FROM creator_creation_video WHERE run_id=#{runId} ORDER BY position")
    List<CreationVideo> scenes(@Param("runId") String runId);

    @Select("SELECT * FROM creator_creation_video WHERE run_id=#{runId} AND scene_id=#{sceneId}")
    Optional<CreationVideo> scene(@Param("runId") String runId, @Param("sceneId") String sceneId);

    /** 同一场景只允许一个执行者从排队进入提交，重复扫描不会重复调用供应商。 */
    @Update("""
            UPDATE creator_creation_video v SET v.status='SUBMITTING',v.provider=#{provider},v.model=#{model},
                v.requested_seconds=#{seconds},v.error_code=NULL
            WHERE v.run_id=#{runId} AND v.scene_id=#{sceneId} AND v.status='QUEUED' """ + LEASE_FENCE)
    int beginSubmit(@Param("runId") String runId, @Param("sceneId") String sceneId, @Param("provider") String provider,
                    @Param("model") String model, @Param("seconds") int seconds,
                    @Param("worker") String worker, @Param("generation") long generation);

    /** 上次进程在提交前中断、从未发送时，安全回到排队状态再重试。 */
    @Update("UPDATE creator_creation_video SET status='QUEUED',error_code=NULL WHERE run_id=#{runId} AND scene_id=#{sceneId} AND status='SUBMITTING'")
    int requeue(@Param("runId") String runId, @Param("sceneId") String sceneId);

    @Update("""
            UPDATE creator_creation_video v SET v.status='SUBMITTED',v.provider_task_id=#{taskId},
                v.provider_request_id=COALESCE(#{requestId},v.provider_request_id),v.error_code=NULL
            WHERE v.run_id=#{runId} AND v.scene_id=#{sceneId} AND v.status='SUBMITTING' """ + LEASE_FENCE)
    int submitted(@Param("runId") String runId, @Param("sceneId") String sceneId, @Param("taskId") String taskId,
                  @Param("requestId") String requestId, @Param("worker") String worker, @Param("generation") long generation);

    @Update("""
            UPDATE creator_creation_video v SET v.status='READY',v.video_asset_id=#{assetId},v.video_sha256=#{sha256},
                v.video_bytes=#{bytes},v.video_duration_ms=#{durationMs},v.video_width=#{width},v.video_height=#{height},
                v.error_code=NULL
            WHERE v.run_id=#{runId} AND v.scene_id=#{sceneId} AND v.status='SUBMITTED' """ + LEASE_FENCE)
    int ready(@Param("runId") String runId, @Param("sceneId") String sceneId, @Param("assetId") String assetId,
              @Param("sha256") String sha256, @Param("bytes") long bytes, @Param("durationMs") long durationMs,
              @Param("width") int width, @Param("height") int height,
              @Param("worker") String worker, @Param("generation") long generation);

    /** 待作者处理的片段也要保存真实素材事实，缩短场景后可以直接复用，不必再次付费。 */
    @Update("""
            UPDATE creator_creation_video v SET v.status='NEEDS_REVIEW',
                v.video_asset_id=COALESCE(#{assetId},v.video_asset_id),
                v.video_sha256=COALESCE(#{sha256},v.video_sha256),
                v.video_bytes=COALESCE(#{bytes},v.video_bytes),
                v.video_duration_ms=COALESCE(#{durationMs},v.video_duration_ms),
                v.video_width=COALESCE(#{width},v.video_width),
                v.video_height=COALESCE(#{height},v.video_height),
                v.proposal_json=#{proposal},v.error_code=#{code}
            WHERE v.run_id=#{runId} AND v.scene_id=#{sceneId} AND v.status='SUBMITTED' """ + LEASE_FENCE)
    int review(@Param("runId") String runId, @Param("sceneId") String sceneId, @Param("assetId") String assetId,
               @Param("sha256") String sha256, @Param("bytes") Long bytes, @Param("durationMs") Long durationMs,
               @Param("width") Integer width, @Param("height") Integer height,
               @Param("proposal") String proposal, @Param("code") String code,
               @Param("worker") String worker, @Param("generation") long generation);

    @Update("""
            UPDATE creator_creation_video v SET v.status='FAILED',v.error_code=#{code}
            WHERE v.run_id=#{runId} AND v.scene_id=#{sceneId}
                AND v.status NOT IN ('READY','NEEDS_REVIEW','FAILED','CANCELLED') """ + LEASE_FENCE)
    int fail(@Param("runId") String runId, @Param("sceneId") String sceneId, @Param("code") String code,
             @Param("worker") String worker, @Param("generation") long generation);

    /** 运行已结束但场景还停在中间态时统一收口，避免长期显示为提交中。 */
    @Update("UPDATE creator_creation_video SET status=#{status},error_code=#{code} WHERE run_id=#{runId} AND status NOT IN ('READY','NEEDS_REVIEW','FAILED','CANCELLED')")
    int closeByRun(@Param("runId") String runId, @Param("status") String status, @Param("code") String code);

    @Select("""
            SELECT DISTINCT j.run_id,j.task_id,j.owner_id,r.status AS run_status FROM creator_creation_video_job j
            JOIN creator_creation_run r ON r.run_id=j.run_id AND r.task_id=j.task_id AND r.owner_id=j.owner_id
            WHERE r.status IN ('CANCELLED','FAILED') AND EXISTS(SELECT 1 FROM creator_creation_video v
                WHERE v.run_id=j.run_id AND v.status NOT IN ('READY','NEEDS_REVIEW','FAILED','CANCELLED'))
            LIMIT 20
            """)
    List<RunRef> terminalWithPending();

    record RunRef(String runId, String taskId, String ownerId, String runStatus) { }

    @Select("SELECT * FROM creator_creation_asset WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND idempotency_key=#{key}")
    Optional<com.link.linkagent.creator.creation.asset.model.CreationAsset> assetByKey(@Param("taskId") String taskId,
            @Param("ownerId") String ownerId, @Param("key") String key);

    /** 未变镜头的片段可以复用：提示词一致、仍是可用素材、时长覆盖当前场景，再交给新故事板引用。 */
    @Select("""
            SELECT v.* FROM creator_creation_video v
            JOIN creator_creation_asset a ON a.asset_id=v.video_asset_id AND a.task_id=v.task_id
                AND a.owner_id=v.owner_id AND a.status='READY'
            WHERE v.task_id=#{taskId} AND v.owner_id=#{ownerId} AND v.prompt_sha256=#{sha256}
                AND v.status IN ('READY','NEEDS_REVIEW') AND v.video_asset_id IS NOT NULL
                AND v.video_duration_ms>=#{minDurationMs}
            ORDER BY v.update_time DESC LIMIT 1
            """)
    Optional<CreationVideo> reusable(@Param("taskId") String taskId, @Param("ownerId") String ownerId,
            @Param("sha256") String promptSha256, @Param("minDurationMs") long minDurationMs);

    /**
     * 生成的视频没有浏览器上传过程，直接登记为 READY；调用方已在事务外复核真实字节与解码结果，
     * 这里只保存已经验证的事实，不能当作免检入口给用户上传使用。
     */
    @Insert("""
            INSERT INTO creator_creation_asset(asset_id,task_id,owner_id,idempotency_key,kind,file_name,content_type,
                expected_bytes,expected_sha256,bucket_name,source_key,status,source_sha256,metadata_json,objects_json)
            VALUES(#{assetId},#{taskId},#{ownerId},#{key},'VIDEO_CLIP',#{fileName},'video/mp4',
                #{bytes},#{sha256},#{bucket},#{sourceKey},'READY',#{sha256},#{metadata},#{objects})
            """)
    int insertGeneratedAsset(@Param("assetId") String assetId, @Param("taskId") String taskId,
            @Param("ownerId") String ownerId, @Param("key") String key, @Param("fileName") String fileName,
            @Param("bytes") long bytes, @Param("sha256") String sha256, @Param("bucket") String bucket,
            @Param("sourceKey") String sourceKey, @Param("metadata") String metadata, @Param("objects") String objects);
}
