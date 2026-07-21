package com.link.linkagent.creator.media.upload.mapper;

import com.link.linkagent.creator.media.upload.model.DraftVideoRecord;
import com.link.linkagent.creator.media.upload.model.MediaUploadPartRecord;
import com.link.linkagent.creator.media.upload.model.MediaUploadRecord;
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
 *   <li>行级锁（FOR UPDATE）只在替换上传会话时使用，范围最小化</li>
 * </ul>
 * <p>
 * P0 简化：version_no 固定为 1，current_review_id 等 P0-1/P0-2 字段待后续扩展。
 */
@Mapper
public interface MediaUploadMapper {

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

    // ========== 成片（creator_draft_video） ==========

    /**
     * 按 taskId + ownerId 查找 P0 版本（version_no=1）的成片记录。
     * P0 每个任务只有一个版本，所以 LIMIT 1。
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
                   create_time,
                   update_time
            FROM creator_draft_video
            WHERE task_id = #{taskId}
              AND owner_id = #{ownerId}
              AND version_no = 1               -- P0 固定版本号
              AND is_deleted = 0
            LIMIT 1
            """)
    Optional<DraftVideoRecord> findDraftVideo(@Param("taskId") String taskId,
                                               @Param("ownerId") String ownerId);

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
              AND is_deleted = 0
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
