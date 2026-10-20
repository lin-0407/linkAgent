package com.link.linkagent.creator.media.upload.mapper;

import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.media.upload.model.MediaUploadPartRecord;
import com.link.linkagent.creator.media.upload.model.MediaUploadRecord;
import com.link.linkagent.creator.creation.model.CreationWorkspaceRecord;
import com.link.linkagent.creator.creation.model.VideoVersionContextRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 阶段 7 P0 媒体上传数据访问层。
 * <p>
 * 安全设计原则：
 * <ul>
 *   <li>所有读取上传或成片的方法都同时携带 taskId 和 ownerId，避免先按 ID 查询再在内存判断归属</li>
 *   <li>状态更新全部使用 CAS（Compare-And-Swap）模式，WHERE 条件包含当前状态，
 *       通过受影响行数判断是否成功，防止并发覆盖</li>
 *   <li>任务行锁串行化版本分配、上传完成和 BV 绑定，网络操作始终在锁外</li>
 * </ul>
 * <p>
 * 无工作区的旧任务回退 V1；新上传分配独立版本，完整旧版不再重置。
 */
@Mapper
public interface MediaUploadMapper {

    /** 系统成片必须固定真实确认与专属蓝图，不能使用任务的已有成片跳过标志。 */
    @Select("""
            SELECT JSON_OBJECT('schemaVersion',1,'source','SYSTEM_RENDER','planningSkipped',0,
                'publishingPlan',JSON_OBJECT('suggestionId',g.suggestion_id,'contentSummary',g.content_summary,
                    'audienceProfile',g.audience_profile,'contentPositioning',g.content_positioning,
                    'titles',g.title_suggestions,'description',g.description_suggestion),
                'productionPlan',JSON_OBJECT('planId',p.plan_id,'title',p.plan_title,'corePromise',p.core_promise,
                    'targetDurationMs',p.target_duration_ms,'productionMethod',p.production_method,'sourceSnapshot',p.source_snapshot,
                    'steps',(SELECT JSON_ARRAYAGG(JSON_OBJECT('stepId',x.step_id,'name',x.step_name,'status',x.status,'skipReason',x.skip_reason))
                        FROM creator_production_step x WHERE x.plan_id=p.plan_id AND x.task_id=t.task_id AND x.is_deleted=0)),
                'materials',(SELECT JSON_OBJECTAGG(m.material_type,m.content) FROM creator_material m
                    WHERE m.task_id=t.task_id AND m.is_deleted=0))
            FROM creator_task t
            JOIN creator_workflow_session s ON s.task_id=t.task_id AND s.user_id=t.user_id AND s.session_id=#{sessionId}
                AND s.stage='PRE_PUBLISH' AND s.status='CONFIRMED' AND s.confirmed_result_id=#{resultId} AND s.is_deleted=0
            JOIN creator_suggestion g ON g.task_id=t.task_id AND g.suggestion_id=#{resultId} AND g.is_deleted=0
            JOIN creator_production_plan p ON p.task_id=t.task_id AND p.owner_id=t.user_id AND p.plan_id=#{planId}
                AND p.production_method='AI_GENERATION' AND p.status='READY' AND p.is_deleted=0
            WHERE t.task_id=#{taskId} AND t.user_id=#{ownerId} AND t.is_deleted=0
                AND (#{latest}=0 OR s.id=(SELECT MAX(s2.id) FROM creator_workflow_session s2
                    WHERE s2.task_id=t.task_id AND s2.stage='PRE_PUBLISH' AND s2.is_deleted=0))
                AND (#{complete}=0 OR (EXISTS (SELECT 1 FROM creator_production_step x WHERE x.task_id=t.task_id AND x.plan_id=p.plan_id AND x.is_deleted=0)
                    AND NOT EXISTS (SELECT 1 FROM creator_production_step x
                    WHERE x.task_id=t.task_id AND x.plan_id=p.plan_id AND x.is_deleted=0 AND x.status NOT IN ('COMPLETED','SKIPPED')))
                )
            """)
    Optional<String> findGeneratedProductionContext(@Param("taskId") String taskId, @Param("ownerId") String ownerId,
            @Param("sessionId") String sessionId, @Param("resultId") String resultId, @Param("planId") String planId,
            @Param("complete") boolean complete, @Param("latest") boolean latest);

    @Select("""
            SELECT COUNT(*) FROM creator_creation_run WHERE task_id=#{taskId} AND owner_id=#{ownerId}
                AND kind IN ('FULL','REVISION') AND JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(request_json),request_json,'{}'),'$.productionPlanId'))=#{planId}
            """)
    int countProductionPlanRuns(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("planId") String planId);

    @Select("""
            SELECT o.object_key FROM creator_creation_render_attempt a
            JOIN JSON_TABLE(a.objects_json,'$[*]' COLUMNS(object_key VARCHAR(1000) PATH '$.objectKey')) o
            WHERE a.task_id=#{taskId} AND a.owner_id=#{ownerId} AND a.version_id=#{versionId} AND a.status='REGISTERED'
            """)
    List<String> listGeneratedObjectKeys(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("versionId") String versionId);

    @Select("""
            SELECT version_id, task_id, owner_id, planning_skipped, publishing_session_id,
                   publishing_result_id, production_plan_id, snapshot_json
            FROM creator_video_version_context
            WHERE version_id = #{versionId} AND task_id = #{taskId} AND owner_id = #{ownerId}
            """)
    Optional<VideoVersionContextRecord> findVersionContext(@Param("taskId") String taskId,
            @Param("ownerId") String ownerId, @Param("versionId") String versionId);

    /** 只在真实确认和蓝图就绪时捕获，不把后来变化的任务状态补写到历史版本。 */
    @Insert("""
            INSERT INTO creator_video_version_context (version_id, task_id, owner_id, planning_skipped,
                publishing_session_id, publishing_result_id, production_plan_id, snapshot_json)
            SELECT d.version_id, t.task_id, t.user_id, t.planning_skipped,
                IF(t.planning_skipped = 1, NULL, s.session_id),
                IF(t.planning_skipped = 1, NULL, s.confirmed_result_id),
                IF(t.planning_skipped = 1, NULL, p.plan_id),
                JSON_OBJECT('schemaVersion', 1, 'source', 'UPLOAD', 'planningSkipped', t.planning_skipped,
                    'publishingPlan', IF(t.planning_skipped = 1, NULL, JSON_OBJECT(
                        'suggestionId', g.suggestion_id, 'contentSummary', g.content_summary,
                        'audienceProfile', g.audience_profile, 'contentPositioning', g.content_positioning,
                        'titles', g.title_suggestions, 'description', g.description_suggestion)),
                    'productionPlan', IF(t.planning_skipped = 1, NULL, JSON_OBJECT(
                        'planId', p.plan_id, 'title', p.plan_title, 'corePromise', p.core_promise,
                        'targetDurationMs', p.target_duration_ms, 'productionMethod', p.production_method,
                        'sourceSnapshot', p.source_snapshot,
                        'steps', (SELECT JSON_ARRAYAGG(JSON_OBJECT('stepId', x.step_id,
                            'name', x.step_name, 'status', x.status, 'skipReason', x.skip_reason))
                            FROM creator_production_step x WHERE x.plan_id = p.plan_id
                            AND x.task_id = t.task_id AND x.is_deleted = 0))),
                    'materials', (SELECT JSON_OBJECTAGG(m.material_type, m.content)
                        FROM creator_material m WHERE m.task_id = t.task_id AND m.is_deleted = 0))
            FROM creator_draft_video d
            JOIN creator_task t ON t.task_id = d.task_id AND t.user_id = d.owner_id AND t.is_deleted = 0
            LEFT JOIN creator_workflow_session s ON s.id = (
                SELECT id FROM creator_workflow_session WHERE task_id = t.task_id
                AND stage = 'PRE_PUBLISH' AND is_deleted = 0 ORDER BY id DESC LIMIT 1)
            LEFT JOIN creator_suggestion g ON g.suggestion_id = s.confirmed_result_id
                AND g.task_id = t.task_id AND g.is_deleted = 0
            LEFT JOIN creator_production_plan p ON p.id = (
                SELECT id FROM creator_production_plan WHERE task_id = t.task_id AND owner_id = t.user_id
                AND status <> 'STALE' AND is_deleted = 0 ORDER BY plan_version DESC, id DESC LIMIT 1)
            WHERE d.version_id = #{versionId} AND d.task_id = #{taskId} AND d.owner_id = #{ownerId}
                AND d.is_deleted = 0
                AND (t.planning_skipped = 1 OR (s.status = 'CONFIRMED' AND s.user_id = t.user_id
                    AND g.suggestion_id IS NOT NULL AND p.status = 'READY'
                    AND NOT EXISTS (SELECT 1 FROM creator_production_step x WHERE x.plan_id = p.plan_id
                        AND x.task_id = t.task_id AND x.is_deleted = 0
                        AND x.status NOT IN ('COMPLETED', 'SKIPPED'))))
            """)
    int captureUploadVersionContext(@Param("taskId") String taskId, @Param("ownerId") String ownerId,
                                    @Param("versionId") String versionId);

    // ========== 归属校验 ==========

    /**
     * 校验创作任务是否存在且属于指定 owner。
     * 用于上传创建前的基础归属检查，防止操作不存在的任务。
     *
     * @param taskId  任务 ID
     * @param ownerId 媒体会话归属（P0 固定为 default）
     * @return 1=存在，0=不存在
     */
    @Select("""
            SELECT COUNT(1)
            FROM creator_task
            WHERE task_id = #{taskId}
              AND user_id = #{ownerId}
              AND is_deleted = 0
            """)
    int countTaskByOwner(@Param("taskId") String taskId, @Param("ownerId") String ownerId);

    @Select("""
            SELECT COUNT(1)
            FROM creator_task
            WHERE task_id = #{taskId}
              AND user_id = #{ownerId}
              AND planning_skipped = 1
              AND is_deleted = 0
            """)
    int countPlanningSkippedTask(@Param("taskId") String taskId, @Param("ownerId") String ownerId);

    /** 正式发布链路只使用明确绑定的版本，旧记录为空时不猜测其历史归属。 */
    @Select("""
            SELECT published_version_id FROM creator_task_video_binding
            WHERE task_id = #{taskId} AND user_id = #{ownerId}
              AND is_deleted = 0 AND (binding_status = 'BOUND' OR EXISTS (
                  SELECT 1 FROM creator_draft_video d WHERE d.version_id = published_version_id
                    AND d.task_id = creator_task_video_binding.task_id
                    AND d.owner_id = creator_task_video_binding.user_id
                    AND d.published_flag = 1 AND d.is_deleted = 0))
            LIMIT 1
            """)
    Optional<String> findPublishedVideoVersionId(@Param("taskId") String taskId, @Param("ownerId") String ownerId);

    // ========== 成片（creator_draft_video） ==========

    /**
     * 工作区存在时只读明确指针；只有没有工作区的旧任务才回退 V1。
     *
     * @return 成片记录；未找到时返回 Optional.empty()
     */
    @Select("""
            SELECT id,
                   version_id,
                   task_id,
                   owner_id,
                   version_no,
                   version_name,
                   original_file_name,
                   bucket_name,
                   object_key,
                   content_type,
                   file_size,
                   duration_ms,
                   width,
                   height,
                   frame_rate,
                   video_codec,
                   audio_codec,
                   has_audio,
                   probe_attempt_id,
                   status,
                   media_deleted_at,
                   create_time,
                   update_time
            FROM creator_draft_video
            WHERE task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND (
                    version_id = (SELECT current_video_version_id FROM creator_creation_workspace
                                  WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND is_deleted = 0)
                    OR (version_no = 1 AND NOT EXISTS (
                        SELECT 1 FROM creator_creation_workspace
                        WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND is_deleted = 0
                    ))
                  )
              AND is_deleted = 0
            LIMIT 1
            """)
    Optional<DraftVideoRecord> findDraftVideo(@Param("taskId") String taskId,
                                               @Param("ownerId") String ownerId);

    @Select("""
            SELECT task_id FROM creator_task
            WHERE task_id = #{taskId} AND user_id = #{ownerId} AND is_deleted = 0
            FOR UPDATE
            """)
    Optional<String> lockMediaTask(@Param("taskId") String taskId, @Param("ownerId") String ownerId);

    @Select("SELECT COUNT(*) FROM creator_creation_run WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND status IN ('QUEUED','RUNNING','WAITING_DEPENDENCY','CANCEL_REQUESTED')")
    int countActiveCreationRuns(@Param("taskId") String taskId, @Param("ownerId") String ownerId);

    /** 先锁版本再改其子任务，保持与探测、试映和媒体删除相同的锁顺序。 */
    @Select("""
            SELECT version_id FROM creator_draft_video
            WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND is_deleted = 0
            ORDER BY id FOR UPDATE
            """)
    List<String> lockTaskDraftVersions(@Param("taskId") String taskId, @Param("ownerId") String ownerId);

    @Select("""
            SELECT (SELECT COUNT(*) FROM creator_task_video_binding
                    WHERE task_id = #{taskId} AND user_id = #{ownerId} AND is_deleted = 0)
                 + (SELECT COUNT(*) FROM creator_draft_video
                    WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND published_flag = 1 AND is_deleted = 0)
            """)
    int countPublishingFacts(@Param("taskId") String taskId, @Param("ownerId") String ownerId);

    /** 只在显式写入上传时采纳旧 V1；读取页面不能偷偷创建工作区。 */
    @Insert("""
            INSERT INTO creator_creation_workspace
                (task_id, owner_id, current_video_version_id, next_version_no)
            SELECT task_id, user_id,
                   (SELECT version_id FROM creator_draft_video
                    WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND version_no = 1 AND is_deleted = 0),
                   (SELECT COALESCE(MAX(version_no), 0) + 1 FROM creator_draft_video WHERE task_id = #{taskId})
            FROM creator_task WHERE task_id = #{taskId} AND user_id = #{ownerId} AND is_deleted = 0
            ON DUPLICATE KEY UPDATE task_id = creator_creation_workspace.task_id
            """)
    int adoptMediaWorkspace(@Param("taskId") String taskId, @Param("ownerId") String ownerId);

    @Select("""
            SELECT task_id, owner_id, workspace_revision, current_video_version_id, next_version_no
            FROM creator_creation_workspace
            WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND is_deleted = 0
            """)
    Optional<CreationWorkspaceRecord> findMediaWorkspace(@Param("taskId") String taskId, @Param("ownerId") String ownerId);

    @Update("""
            UPDATE creator_creation_workspace SET next_version_no = next_version_no + 1
            WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND next_version_no = #{expectedNumber}
              AND is_deleted = 0
            """)
    int advanceMediaVersion(@Param("taskId") String taskId, @Param("ownerId") String ownerId,
                            @Param("expectedNumber") int expectedNumber);

    /** 完整对象登记后才切换指针；失败或取消不能把当前好版本替换掉。 */
    @Update("""
            UPDATE creator_creation_workspace SET current_video_version_id = #{versionId},
                workspace_revision = workspace_revision + 1
            WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND is_deleted = 0
              AND workspace_revision = #{expectedRevision}
            """)
    int activateUploadedVersion(@Param("taskId") String taskId, @Param("ownerId") String ownerId,
                                @Param("versionId") String versionId, @Param("expectedRevision") long expectedRevision);

    /** 新方向需要新的成片事实，保留历史媒体和报告，只撤下当前指针。 */
    @Update("""
            UPDATE creator_creation_workspace SET current_video_version_id = NULL,
                workspace_revision = workspace_revision + 1
            WHERE task_id = #{taskId} AND owner_id = #{ownerId} AND is_deleted = 0
              AND workspace_revision = #{expectedRevision}
            """)
    int clearCurrentVideoForReposition(@Param("taskId") String taskId, @Param("ownerId") String ownerId,
                                       @Param("expectedRevision") long expectedRevision);

    /**
     * 按 versionId + taskId + ownerId 查找成片记录。
     * 用于媒体探测接口确认版本归属，避免只按 versionId 查询造成水平越权。
     */
    @Select("""
            SELECT id,
                   version_id,
                   task_id,
                   owner_id,
                   version_no,
                   version_name,
                   original_file_name,
                   bucket_name,
                   object_key,
                   content_type,
                   file_size,
                   duration_ms,
                   width,
                   height,
                   frame_rate,
                   video_codec,
                   audio_codec,
                   has_audio,
                   probe_attempt_id,
                   status,
                   media_deleted_at,
                   create_time,
                   update_time
            FROM creator_draft_video
            WHERE version_id = #{versionId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND is_deleted = 0
            LIMIT 1
            """)
    Optional<DraftVideoRecord> findDraftVideoByVersion(@Param("taskId") String taskId,
                                                       @Param("ownerId") String ownerId,
                                                       @Param("versionId") String versionId);

    /** 查询当前版本的全部上传尝试，主动删除时据此清理历史对象和未完成分片。 */
    @Select("""
            SELECT id,
                   upload_session_id,
                   version_id,
                   task_id,
                   owner_id,
                   storage_upload_id,
                   object_key,
                   content_type,
                   expected_size,
                   file_fingerprint,
                   part_size,
                   total_parts,
                   status,
                   idempotency_key,
                   failure_message,
                   expires_at,
                   completed_at,
                   create_time,
                   update_time
            FROM creator_media_upload
            WHERE task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND version_id = #{versionId}
              AND is_deleted = 0
            ORDER BY id ASC
            """)
    List<MediaUploadRecord> listUploadsByVersion(@Param("taskId") String taskId,
                                                  @Param("ownerId") String ownerId,
                                                  @Param("versionId") String versionId);

    // ========== 上传会话（creator_media_upload） ==========

    /**
     * 按幂等键查找已有上传会话（三要素：taskId + ownerId + idempotencyKey）。
     * 用于幂等重试判断：同一任务同一文件重复请求返回已有会话。
     */
    @Select("""
            SELECT id,
                   upload_session_id,
                   version_id,
                   task_id,
                   owner_id,
                   storage_upload_id,
                   object_key,
                   content_type,
                   expected_size,
                   file_fingerprint,
                   part_size,
                   total_parts,
                   status,
                   idempotency_key,
                   failure_message,
                   expires_at,
                   completed_at,
                   create_time,
                   update_time
            FROM creator_media_upload
            WHERE task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND idempotency_key = #{idempotencyKey}
              AND is_deleted = 0
            LIMIT 1
            """)
    Optional<MediaUploadRecord> findUploadByIdempotency(@Param("taskId") String taskId,
                                                        @Param("ownerId") String ownerId,
                                                        @Param("idempotencyKey") String idempotencyKey);

    /**
     * 按 uploadSessionId + taskId + ownerId 三重校验查找上传会话。
     * 确保只能操作自己的会话，防止水平越权。
     */
    @Select("""
            SELECT id,
                   upload_session_id,
                   version_id,
                   task_id,
                   owner_id,
                   storage_upload_id,
                   object_key,
                   content_type,
                   expected_size,
                   file_fingerprint,
                   part_size,
                   total_parts,
                   status,
                   idempotency_key,
                   failure_message,
                   expires_at,
                   completed_at,
                   create_time,
                   update_time
            FROM creator_media_upload
            WHERE upload_session_id = #{uploadSessionId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND is_deleted = 0
            LIMIT 1
            """)
    Optional<MediaUploadRecord> findUpload(@Param("taskId") String taskId,
                                           @Param("ownerId") String ownerId,
                                           @Param("uploadSessionId") String uploadSessionId);

    /**
     * 查询任务当前仍需客户端处理的上传会话。
     * <p>
     * localStorage 只是便捷指针，不能成为唯一恢复来源；浏览器清理站点数据后仍应能找回服务端事实。
     */
    @Select("""
            SELECT id,
                   upload_session_id,
                   version_id,
                   task_id,
                   owner_id,
                   storage_upload_id,
                   object_key,
                   content_type,
                   expected_size,
                   file_fingerprint,
                   part_size,
                   total_parts,
                   status,
                   idempotency_key,
                   failure_message,
                   expires_at,
                   completed_at,
                   create_time,
                   update_time
            FROM creator_media_upload
            WHERE task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND status IN ('CREATED', 'UPLOADING', 'VERIFYING')
              AND is_deleted = 0
            ORDER BY id DESC
            LIMIT 1
            """)
    Optional<MediaUploadRecord> findCurrentUpload(@Param("taskId") String taskId,
                                                  @Param("ownerId") String ownerId);

    /**
     * 按对象键加行级锁（FOR UPDATE）查找上传会话。
     * <p>
     * 用于替换上传会话前的并发控制：锁定当前行，确保在判断旧状态→标记替代→创建新会话
     * 这个过程中不会有其他事务同时修改同一行。
     * <p>
     * FOR UPDATE 在事务内阻塞其他写操作，事务提交后自动释放锁。
     * 按 id DESC 取最新一条，确保锁的是当前有效的上传尝试。
     */
    @Select("""
            SELECT id,
                   upload_session_id,
                   version_id,
                   task_id,
                   owner_id,
                   storage_upload_id,
                   object_key,
                   content_type,
                   expected_size,
                   file_fingerprint,
                   part_size,
                   total_parts,
                   status,
                   idempotency_key,
                   failure_message,
                   expires_at,
                   completed_at,
                   create_time,
                   update_time
            FROM creator_media_upload
            WHERE version_id = #{versionId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND object_key = #{objectKey}
              AND is_deleted = 0
            ORDER BY id DESC            -- 取最新记录
            LIMIT 1
            FOR UPDATE                  -- 行级排他锁，事务内阻塞并发写操作
            """)
    Optional<MediaUploadRecord> lockUploadByObjectKey(@Param("versionId") String versionId,
                                                       @Param("taskId") String taskId,
                                                       @Param("ownerId") String ownerId,
                                                       @Param("objectKey") String objectKey);

    /**
     * 插入新的成片记录（首次上传）。
     *
     * @return 受影响行数（必须为 1）
     */
    @Insert("""
            INSERT INTO creator_draft_video (
                version_id,
                task_id,
                owner_id,
                version_no,
                version_name,
                original_file_name,
                bucket_name,
                object_key,
                content_type,
                file_size,
                status                     -- 初始状态 UPLOADING
            )
            VALUES (
                #{versionId},
                #{taskId},
                #{ownerId},
                #{versionNo},
                #{versionName},
                #{originalFileName},
                #{bucketName},
                #{objectKey},
                #{contentType},
                #{fileSize},
                #{status}
            )
            """)
    int insertDraftVideo(DraftVideoRecord record);

    /**
     * 重置成片记录用于新上传尝试（复用已有成片）。
     * <p>
     * CAS 条件：
     * <ul>
     *   <li>version_id + task_id + owner_id + object_key 匹配（精确定位当前行）</li>
     *   <li>status IN ('UPLOAD_FAILED', 'UPLOAD_ABORTED', 'PROBE_FAILED')（只有失败/取消后才能重置）</li>
     * </ul>
     * 重置时只更新对象键、文件名等可变字段，keep version_id 不变。
     *
     * @param record            新上传的成片信息
     * @param previousObjectKey 旧尝试的对象键（用于精确定位，防止误更新）
     * @return 受影响行数（必须为 1）
     */
    @Update("""
            UPDATE creator_draft_video
            SET object_key = #{record.objectKey},           -- 更新为新尝试的对象键
                version_name = #{record.versionName},
                original_file_name = #{record.originalFileName},
                bucket_name = #{record.bucketName},
                content_type = #{record.contentType},
                file_size = #{record.fileSize},
                duration_ms = NULL,
                width = NULL,
                height = NULL,
                frame_rate = NULL,
                video_codec = NULL,
                audio_codec = NULL,
                has_audio = NULL,
                probe_attempt_id = NULL,
                status = 'UPLOADING',                       -- 状态重置为上传中
                update_time = CURRENT_TIMESTAMP
            WHERE version_id = #{record.versionId}
              AND task_id = #{record.taskId}
              AND owner_id = #{record.ownerId}
              AND object_key = #{previousObjectKey}         -- 旧对象键精确匹配
              AND status IN ('UPLOAD_FAILED', 'UPLOAD_ABORTED', 'PROBE_FAILED') -- CAS：仅允许从失败/取消/探测失败状态重置
              AND media_deleted_at IS NULL
              AND is_deleted = 0
            """)
    int resetDraftVideoForUpload(@Param("record") DraftVideoRecord record,
                                 @Param("previousObjectKey") String previousObjectKey);

    /**
     * 插入新的上传会话记录。
     * 初始状态为 CREATED，等待首次分片签名时切换为 UPLOADING。
     *
     * @return 受影响行数（必须为 1）
     */
    @Insert("""
            INSERT INTO creator_media_upload (
                upload_session_id,
                version_id,
                task_id,
                owner_id,
                storage_upload_id,
                object_key,
                content_type,
                expected_size,
                file_fingerprint,
                part_size,
                total_parts,
                status,
                idempotency_key,
                expires_at                  -- 过期时间 = 创建时间 + abandonedTtl
            )
            VALUES (
                #{uploadSessionId},
                #{versionId},
                #{taskId},
                #{ownerId},
                #{storageUploadId},
                #{objectKey},
                #{contentType},
                #{expectedSize},
                #{fileFingerprint},
                #{partSize},
                #{totalParts},
                #{status},
                #{idempotencyKey},
                #{expiresAt}
            )
            """)
    int insertUpload(MediaUploadRecord record);

    // ========== 分片（creator_media_upload_part） ==========

    /**
     * 列出指定上传会话的所有已登记分片，按分片序号升序排列。
     * 供前端续传时判断哪些分片已完成，以及完成时校验分片完整性。
     */
    @Select("""
            SELECT id,
                   upload_session_id,
                   part_number,
                   etag,
                   part_size,
                   completed_at,
                   create_time,
                   update_time
            FROM creator_media_upload_part
            WHERE upload_session_id = #{uploadSessionId}
            ORDER BY part_number ASC                    -- 按分片序号升序，便于校验连续性
            """)
    List<MediaUploadPartRecord> listParts(@Param("uploadSessionId") String uploadSessionId);

    /**
     * 幂等写入分片记录（INSERT ... ON DUPLICATE KEY UPDATE）。
     * <p>
     * 唯一键为 (upload_session_id, part_number)，重复写入同一分片时更新 ETag、
     * 大小和完成时间，而不是报唯一键冲突。这样浏览器重试登记时可以安全地重复调用。
     */
    @Insert("""
            INSERT INTO creator_media_upload_part (
                upload_session_id,
                part_number,
                etag,
                part_size,
                completed_at
            )
            VALUES (
                #{uploadSessionId},
                #{partNumber},
                #{etag},
                #{partSize},
                #{completedAt}
            )
            ON DUPLICATE KEY UPDATE                   -- 幂等：重复分片写入时更新而非报错
                etag = VALUES(etag),                  -- 更新 ETag（OSS 每次可能返回不同的 ETag）
                part_size = VALUES(part_size),         -- 更新实际分片大小
                completed_at = VALUES(completed_at),   -- 更新完成时间
                update_time = CURRENT_TIMESTAMP
            """)
    int upsertPart(MediaUploadPartRecord record);

    // ========== 上传会话状态转换（全部使用 CAS 模式） ==========

    /**
     * CAS：CREATED/UPLOADING → UPLOADING（首次签名或续传时调用）。
     * 同时清除之前可能的失败原因。
     * 幂等：已在 UPLOADING 状态时重复调用也返回 1。
     */
    @Update("""
            UPDATE creator_media_upload
            SET status = 'UPLOADING',
                failure_message = NULL,               -- 清除旧失败原因
                update_time = CURRENT_TIMESTAMP
            WHERE upload_session_id = #{uploadSessionId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND status IN ('CREATED', 'UPLOADING')  -- CAS：只有这两个状态允许上传操作
              AND is_deleted = 0
            """)
    int markUploadUploading(@Param("taskId") String taskId,
                            @Param("ownerId") String ownerId,
                            @Param("uploadSessionId") String uploadSessionId);

    /**
     * CAS：UPLOADING → VERIFYING（确认完成时调用）。
     * VERIFYING 是中间态：CompleteMultipartUpload 请求已发送给 OSS 但尚未确认结果。
     * 此状态下其他操作被阻止，防止并发修改。
     */
    @Update("""
            UPDATE creator_media_upload
            SET status = 'VERIFYING',
                failure_message = NULL,
                update_time = CURRENT_TIMESTAMP
            WHERE upload_session_id = #{uploadSessionId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND status = 'UPLOADING'                 -- CAS：仅从上传中状态转移
              AND is_deleted = 0
            """)
    int markUploadVerifying(@Param("taskId") String taskId,
                            @Param("ownerId") String ownerId,
                            @Param("uploadSessionId") String uploadSessionId);

    /**
     * CAS：VERIFYING → FAILED（校验失败时调用）。
     * 记录失败原因摘要（已截断到 500 字符以内）。
     */
    @Update("""
            UPDATE creator_media_upload
            SET status = 'FAILED',
                failure_message = #{failureMessage},   -- 失败原因摘要（≤ 500 字符）
                update_time = CURRENT_TIMESTAMP
            WHERE upload_session_id = #{uploadSessionId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND status = 'VERIFYING'                 -- CAS：仅从确认中转移
              AND is_deleted = 0
            """)
    int failUpload(@Param("taskId") String taskId,
                   @Param("ownerId") String ownerId,
                   @Param("uploadSessionId") String uploadSessionId,
                   @Param("failureMessage") String failureMessage);

    /**
     * 将超时的 VERIFYING 状态回退到 UPLOADING，允许客户端重新发起完成请求。
     * <p>
     * 用于恢复场景：CompleteMultipartUpload 请求可能在 OSS 端成功但响应丢包，
     * 等待 recoveryDelay（默认 5 分钟）后允许客户端重试。
     * 条件：update_time < staleBefore（仅回退超时的记录，保护正在处理中的）。
     */
    @Update("""
            UPDATE creator_media_upload
            SET status = 'UPLOADING',                   -- 回退到上传中，允许重新确认
                failure_message = '上传确认超时，请重新确认',
                update_time = CURRENT_TIMESTAMP
            WHERE upload_session_id = #{uploadSessionId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND status = 'VERIFYING'
              AND update_time < #{staleBefore}          -- 仅回退超时记录（保护正在处理中的）
              AND is_deleted = 0
            """)
    int reopenStaleVerifyingUpload(@Param("taskId") String taskId,
                                   @Param("ownerId") String ownerId,
                                   @Param("uploadSessionId") String uploadSessionId,
                                   @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * CAS：CREATED/UPLOADING → ABORTED（用户主动取消）。
     * 只有未开始确认的会话才能取消；VERIFYING/COMPLETED 不能取消。
     */
    @Update("""
            UPDATE creator_media_upload
            SET status = 'ABORTED',
                failure_message = NULL,
                update_time = CURRENT_TIMESTAMP
            WHERE upload_session_id = #{uploadSessionId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND status IN ('CREATED', 'UPLOADING')   -- CAS：只有未确认的会话才能取消
              AND is_deleted = 0
            """)
    int abortUpload(@Param("taskId") String taskId,
                    @Param("ownerId") String ownerId,
                    @Param("uploadSessionId") String uploadSessionId);

    /**
     * CAS：CREATED/UPLOADING → EXPIRED（超时自动过期）。
     * 条件同时要求 expires_at < CURRENT_TIMESTAMP，数据库级别双重校验确保不会误标记。
     */
    @Update("""
            UPDATE creator_media_upload
            SET status = 'EXPIRED',
                failure_message = '上传会话已过期',
                update_time = CURRENT_TIMESTAMP
            WHERE upload_session_id = #{uploadSessionId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND status IN ('CREATED', 'UPLOADING')   -- CAS：仅在活跃状态可过期
              AND expires_at < CURRENT_TIMESTAMP        -- 数据库级别双重校验过期时间
              AND is_deleted = 0
            """)
    int expireUpload(@Param("taskId") String taskId,
                     @Param("ownerId") String ownerId,
                     @Param("uploadSessionId") String uploadSessionId);

    /**
     * CAS：FAILED / COMPLETED → SUPERSEDED（被新上传尝试替代）。
     * COMPLETED 仅用于成片探测失败后的替换，避免旧会话继续声称其已删除对象仍然有效。
     */
    @Update("""
            UPDATE creator_media_upload
            SET status = 'SUPERSEDED',
                failure_message = '已创建新的上传会话',
                update_time = CURRENT_TIMESTAMP
            WHERE upload_session_id = #{uploadSessionId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND status IN ('FAILED', 'COMPLETED')      -- 上传失败或探测失败后的完整对象可被替代
              AND is_deleted = 0
            """)
    int supersedeReplacedUpload(@Param("taskId") String taskId,
                                @Param("ownerId") String ownerId,
                                @Param("uploadSessionId") String uploadSessionId);

    /**
     * CAS：VERIFYING → COMPLETED（确认完成，最终成功态）。
     * 同时记录完成时间，供后续统计和清理使用。
     */
    @Update("""
            UPDATE creator_media_upload
            SET status = 'COMPLETED',
                failure_message = NULL,
                completed_at = #{completedAt},          -- 记录完成时间戳
                update_time = CURRENT_TIMESTAMP
            WHERE upload_session_id = #{uploadSessionId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND status = 'VERIFYING'                  -- CAS：仅从确认中转移
              AND is_deleted = 0
            """)
    int completeUpload(@Param("taskId") String taskId,
                       @Param("ownerId") String ownerId,
                       @Param("uploadSessionId") String uploadSessionId,
                       @Param("completedAt") LocalDateTime completedAt);

    /**
     * CAS 更新成片状态。
     * <p>
     * 同时更新 contentType 和 fileSize 为 HeadObject 结果为准的实际值，
     * 不信任客户端声明的值。
     * CAS 条件：status IN ('UPLOADING', 'UPLOAD_FAILED', 'UPLOAD_ABORTED')，
     * 防止在已发布或其他终态上做修改。
     */
    @Update("""
            UPDATE creator_draft_video
            SET status = #{status},
                content_type = #{contentType},    -- 以对象存储实际类型为准
                file_size = #{fileSize},          -- 以对象存储实际大小为准
                update_time = CURRENT_TIMESTAMP
            WHERE version_id = #{versionId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND object_key = #{objectKey}
              AND status IN ('UPLOADING', 'UPLOAD_FAILED', 'UPLOAD_ABORTED') -- CAS 状态条件
              AND is_deleted = 0
            """)
    int updateDraftVideoStatus(@Param("taskId") String taskId,
                               @Param("ownerId") String ownerId,
                               @Param("versionId") String versionId,
                               @Param("objectKey") String objectKey,
                               @Param("status") String status,
                               @Param("contentType") String contentType,
                               @Param("fileSize") long fileSize);

    /** 媒体对象删除后关闭全部上传会话，避免旧幂等键继续恢复一个已经不存在的文件。 */
    @Update("""
            UPDATE creator_media_upload
            SET is_deleted = 1,
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND version_id = #{versionId}
              AND is_deleted = 0
            """)
    int markUploadsDeleted(@Param("taskId") String taskId,
                           @Param("ownerId") String ownerId,
                           @Param("versionId") String versionId);

    /** 只记录实际完成的用户删除，保留原状态供发布后流程继续识别既有试映结果。 */
    @Update("""
            UPDATE creator_draft_video
            SET media_deleted_at = CURRENT_TIMESTAMP,
                delete_reason = 'USER_REQUEST',
                update_time = CURRENT_TIMESTAMP
            WHERE task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND version_id = #{versionId}
              AND media_deleted_at IS NULL
              AND is_deleted = 0
            """)
    int markDraftMediaDeleted(@Param("taskId") String taskId,
                              @Param("ownerId") String ownerId,
                              @Param("versionId") String versionId);

    /**
     * 原子领取一次媒体探测。
     * <p>
     * 服务意外中断后的 PROBING 状态会在读取时先懒恢复为 PROBE_FAILED，再重新领取，
     * 因此这里仅领取可立即开始的状态。
     */
    @Update("""
            UPDATE creator_draft_video
            SET status = 'PROBING',
                probe_attempt_id = #{probeAttemptId},
                duration_ms = NULL,
                width = NULL,
                height = NULL,
                frame_rate = NULL,
                video_codec = NULL,
                audio_codec = NULL,
                has_audio = NULL,
                update_time = CURRENT_TIMESTAMP
            WHERE version_id = #{versionId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND status IN ('UPLOADED', 'PROBE_FAILED')
              AND media_deleted_at IS NULL
              AND is_deleted = 0
              AND NOT EXISTS (SELECT 1 FROM creator_creation_render_attempt a JOIN creator_creation_run r
                  ON r.run_id=a.run_id AND r.task_id=a.task_id AND r.owner_id=a.owner_id
                  WHERE a.version_id=creator_draft_video.version_id AND a.task_id=creator_draft_video.task_id
                    AND a.owner_id=creator_draft_video.owner_id AND a.status='REGISTERED'
                    AND r.status IN ('QUEUED','RUNNING','WAITING_DEPENDENCY','CANCEL_REQUESTED'))
            """)
    int claimDraftVideoProbe(@Param("taskId") String taskId,
                             @Param("ownerId") String ownerId,
                             @Param("versionId") String versionId,
                             @Param("probeAttemptId") String probeAttemptId);

    /**
     * 懒恢复长时间未结束的媒体探测。
     * <p>
     * 探测没有独立 Worker 或租约，本切片通过读取时把超时 PROBING 收敛为 PROBE_FAILED，
     * 让用户可以重试，而不是在服务重启后永久卡住。
     */
    @Update("""
            UPDATE creator_draft_video
            SET status = 'PROBE_FAILED',
                probe_attempt_id = NULL,
                update_time = CURRENT_TIMESTAMP
            WHERE version_id = #{versionId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND status = 'PROBING'
              AND update_time < #{staleBefore}
              AND is_deleted = 0
              AND NOT EXISTS (SELECT 1 FROM creator_creation_render_attempt a JOIN creator_creation_run r
                  ON r.run_id=a.run_id AND r.task_id=a.task_id AND r.owner_id=a.owner_id
                  WHERE a.version_id=creator_draft_video.version_id AND a.task_id=creator_draft_video.task_id
                    AND a.owner_id=creator_draft_video.owner_id AND a.status='REGISTERED'
                    AND r.status IN ('QUEUED','RUNNING','WAITING_DEPENDENCY','CANCEL_REQUESTED'))
            """)
    int recoverStaleDraftVideoProbe(@Param("taskId") String taskId,
                                    @Param("ownerId") String ownerId,
                                    @Param("versionId") String versionId,
                                    @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * 写入 ffprobe 媒体探测结果。
     * <p>
     * 成功时写 READY_FOR_REVIEW 和完整元信息；失败时写 PROBE_FAILED 并保留空元信息。
     * 只有已领取的 PROBING 状态可以写入最终结果，避免并发请求中的晚到结果覆盖已经写入的较新探测结果。
     */
    @Update("""
            UPDATE creator_draft_video
            SET status = #{status},
                probe_attempt_id = NULL,
                duration_ms = #{durationMs},
                width = #{width},
                height = #{height},
                frame_rate = #{frameRate},
                video_codec = #{videoCodec},
                audio_codec = #{audioCodec},
                has_audio = #{hasAudio},
                update_time = CURRENT_TIMESTAMP
            WHERE version_id = #{versionId}
              AND task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND status = #{expectedStatus}
              AND probe_attempt_id = #{probeAttemptId}
              AND is_deleted = 0
            """)
    int updateDraftVideoProbeResult(@Param("taskId") String taskId,
                                    @Param("ownerId") String ownerId,
                                    @Param("versionId") String versionId,
                                    @Param("expectedStatus") String expectedStatus,
                                    @Param("probeAttemptId") String probeAttemptId,
                                    @Param("status") String status,
                                    @Param("durationMs") Long durationMs,
                                    @Param("width") Integer width,
                                    @Param("height") Integer height,
                                    @Param("frameRate") java.math.BigDecimal frameRate,
                                    @Param("videoCodec") String videoCodec,
                                    @Param("audioCodec") String audioCodec,
                                    @Param("hasAudio") Boolean hasAudio);

    /**
     * 删除指定上传会话的所有分片记录。
     * 用于取消上传时清理已登记分片数据，释放存储空间。
     */
    @Delete("""
            DELETE FROM creator_media_upload_part
            WHERE upload_session_id = #{uploadSessionId}
            """)
    int deleteParts(@Param("uploadSessionId") String uploadSessionId);
}
