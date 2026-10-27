package com.link.linkagent.creator.creation.voice.mapper;

import com.link.linkagent.creator.creation.voice.model.CreationVoice;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** 配音任务与场景事实；所有成功写入都带运行租约围栏，迟到的旧执行不能覆盖新代结果。 */
@Mapper
public interface CreationVoiceMapper {

    String LEASE_FENCE = """
            AND EXISTS(SELECT 1 FROM creator_creation_run r WHERE r.run_id=v.run_id AND r.task_id=v.task_id
                AND r.owner_id=v.owner_id AND r.status='RUNNING' AND r.lease_owner=#{worker}
                AND r.lease_generation=#{generation} AND r.lease_expires_at>CURRENT_TIMESTAMP(6))
            """;

    @Insert("""
            INSERT INTO creator_creation_voice_job(run_id,task_id,owner_id,brief_id,storyboard_id,idempotency_key,request_hash)
            VALUES(#{runId},#{taskId},#{ownerId},#{briefId},#{storyboardId},#{key},#{requestHash})
            """)
    int insertJob(@Param("runId") String runId, @Param("taskId") String taskId, @Param("ownerId") String ownerId,
                  @Param("briefId") String briefId, @Param("storyboardId") String storyboardId,
                  @Param("key") String key, @Param("requestHash") String requestHash);

    @Select("SELECT run_id,task_id,owner_id,brief_id,storyboard_id,request_hash,storyboard_result_id "
            + "FROM creator_creation_voice_job WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND idempotency_key=#{key}")
    Optional<Job> jobByKey(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("key") String key);

    @Select("SELECT run_id,task_id,owner_id,brief_id,storyboard_id,request_hash,storyboard_result_id "
            + "FROM creator_creation_voice_job WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND run_id=#{runId}")
    Optional<Job> job(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("runId") String runId);

    @Update("UPDATE creator_creation_voice_job SET storyboard_result_id=#{board} WHERE run_id=#{runId} AND storyboard_result_id IS NULL")
    int result(@Param("runId") String runId, @Param("board") String board);

    @Select("""
            SELECT j.run_id,j.task_id,j.owner_id,j.brief_id,j.storyboard_id,j.request_hash,j.storyboard_result_id
            FROM creator_creation_voice_job j
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
               String requestHash, String storyboardResultId) { }

    @Insert("""
            INSERT INTO creator_creation_voice(run_id,scene_id,task_id,owner_id,brief_id,storyboard_id,position,status,
                text_content,text_sha256,characters,scene_duration_ms)
            VALUES(#{runId},#{sceneId},#{taskId},#{ownerId},#{briefId},#{storyboardId},#{position},'QUEUED',
                #{textContent},#{textSha256},#{characters},#{sceneDurationMs})
            """)
    int insert(CreationVoice voice);

    @Select("SELECT * FROM creator_creation_voice WHERE run_id=#{runId} ORDER BY position")
    List<CreationVoice> scenes(@Param("runId") String runId);

    @Select("SELECT * FROM creator_creation_voice WHERE run_id=#{runId} AND scene_id=#{sceneId}")
    Optional<CreationVoice> scene(@Param("runId") String runId, @Param("sceneId") String sceneId);

    /** 同一场景只允许一个执行者从排队进入合成，重复扫描不会重复调用供应商。 */
    @Update("UPDATE creator_creation_voice SET status='SYNTHESIZING',error_code=NULL WHERE run_id=#{runId} AND scene_id=#{sceneId} AND status='QUEUED'")
    int beginSynthesis(@Param("runId") String runId, @Param("sceneId") String sceneId);

    @Update("""
            UPDATE creator_creation_voice v SET v.status='SYNTHESIZING',v.provider=#{provider},v.model=#{model},
                v.voice_id=#{voice},v.error_code=NULL
            WHERE v.run_id=#{runId} AND v.scene_id=#{sceneId} AND v.status='QUEUED' """ + LEASE_FENCE)
    int beginSynthesisWith(@Param("runId") String runId, @Param("sceneId") String sceneId,
                           @Param("provider") String provider, @Param("model") String model,
                           @Param("voice") String voice, @Param("worker") String worker,
                           @Param("generation") long generation);

    /** 上次进程在调用前中断、从未发送时，安全回到排队状态再重试。 */
    @Update("UPDATE creator_creation_voice SET status='QUEUED',error_code=NULL WHERE run_id=#{runId} AND scene_id=#{sceneId} AND status='SYNTHESIZING'")
    int requeue(@Param("runId") String runId, @Param("sceneId") String sceneId);

    @Update("""
            UPDATE creator_creation_voice v SET v.status='SYNTHESIZED',v.audio_asset_id=#{assetId},
                v.audio_sha256=#{sha256},v.audio_bytes=#{bytes},v.audio_duration_ms=#{durationMs},
                v.provider_request_id=COALESCE(#{requestId},v.provider_request_id),v.error_code=NULL
            WHERE v.run_id=#{runId} AND v.scene_id=#{sceneId} AND v.status='SYNTHESIZING' """ + LEASE_FENCE)
    int synthesized(@Param("runId") String runId, @Param("sceneId") String sceneId, @Param("assetId") String assetId,
                    @Param("sha256") String sha256, @Param("bytes") long bytes, @Param("durationMs") long durationMs,
                    @Param("requestId") String requestId, @Param("worker") String worker, @Param("generation") long generation);

    @Update("""
            UPDATE creator_creation_voice v SET v.status='TRANSCRIBING',v.provider_task_id=#{taskId},v.error_code=NULL
            WHERE v.run_id=#{runId} AND v.scene_id=#{sceneId} AND v.status IN ('SYNTHESIZED','TRANSCRIBING') """ + LEASE_FENCE)
    int transcribing(@Param("runId") String runId, @Param("sceneId") String sceneId, @Param("taskId") String providerTaskId,
                     @Param("worker") String worker, @Param("generation") long generation);

    @Update("""
            UPDATE creator_creation_voice v SET v.status=#{status},v.transcript_json=#{transcript},v.match_ratio=#{ratio},
                v.proposal_json=#{proposal},v.error_code=#{error}
            WHERE v.run_id=#{runId} AND v.scene_id=#{sceneId} AND v.status IN ('SYNTHESIZED','TRANSCRIBING') """ + LEASE_FENCE)
    int aligned(@Param("runId") String runId, @Param("sceneId") String sceneId, @Param("status") String status,
                @Param("transcript") String transcript, @Param("ratio") BigDecimal ratio,
                @Param("proposal") String proposal, @Param("error") String error,
                @Param("worker") String worker, @Param("generation") long generation);

    /** 失败只由当前执行代写入，旧代或取消后的迟到失败不能改写已对齐结果。 */
    @Update("""
            UPDATE creator_creation_voice v SET v.status='FAILED',v.error_code=#{code}
            WHERE v.run_id=#{runId} AND v.scene_id=#{sceneId} AND v.status NOT IN ('ALIGNED','NEEDS_REVIEW','FAILED','CANCELLED') """ + LEASE_FENCE)
    int fail(@Param("runId") String runId, @Param("sceneId") String sceneId, @Param("code") String code,
             @Param("worker") String worker, @Param("generation") long generation);

    /** 运行已结束但场景还停在中间态时统一收口，避免长期显示为排队或合成中。 */
    @Update("UPDATE creator_creation_voice SET status=#{status},error_code=#{code} WHERE run_id=#{runId} AND status NOT IN ('ALIGNED','NEEDS_REVIEW','FAILED','CANCELLED')")
    int closeByRun(@Param("runId") String runId, @Param("status") String status, @Param("code") String code);

    /** 取消或失败的运行不会再被领取，遗留的场景行由独立扫描收口，避免长期显示为排队中。 */
    @Select("""
            SELECT DISTINCT j.run_id,j.task_id,j.owner_id,r.status AS run_status FROM creator_creation_voice_job j
            JOIN creator_creation_run r ON r.run_id=j.run_id AND r.task_id=j.task_id AND r.owner_id=j.owner_id
            WHERE r.status IN ('CANCELLED','FAILED') AND EXISTS(SELECT 1 FROM creator_creation_voice v
                WHERE v.run_id=j.run_id AND v.status NOT IN ('ALIGNED','NEEDS_REVIEW','FAILED','CANCELLED'))
            LIMIT 20
            """)
    List<RunRef> terminalWithPending();

    record RunRef(String runId, String taskId, String ownerId, String runStatus) { }

    @Select("SELECT * FROM creator_creation_asset WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND idempotency_key=#{key}")
    Optional<com.link.linkagent.creator.creation.asset.model.CreationAsset> assetByKey(@Param("taskId") String taskId,
            @Param("ownerId") String ownerId, @Param("key") String key);

    /**
     * 生成的配音没有浏览器上传过程，直接登记为 READY；调用方已在事务外复核真实字节与解码结果，
     * 这里只保存已经验证的事实，不能当作免检入口给用户上传使用。
     */
    @Insert("""
            INSERT INTO creator_creation_asset(asset_id,task_id,owner_id,idempotency_key,kind,file_name,content_type,
                expected_bytes,expected_sha256,bucket_name,source_key,status,source_sha256,metadata_json,objects_json)
            VALUES(#{assetId},#{taskId},#{ownerId},#{key},'NARRATION',#{fileName},'audio/wav',
                #{bytes},#{sha256},#{bucket},#{sourceKey},'READY',#{sha256},#{metadata},#{objects})
            """)
    int insertGeneratedAsset(@Param("assetId") String assetId, @Param("taskId") String taskId,
            @Param("ownerId") String ownerId, @Param("key") String key, @Param("fileName") String fileName,
            @Param("bytes") long bytes, @Param("sha256") String sha256, @Param("bucket") String bucket,
            @Param("sourceKey") String sourceKey, @Param("metadata") String metadata, @Param("objects") String objects);
}
