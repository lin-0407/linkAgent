package com.link.linkagent.creator.creation.mapper;

import com.link.linkagent.creator.creation.model.CreationVersionSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CreationWorkspaceMapper {
    String COMPLETE_VERSIONS = """
            FROM creator_draft_video d
            WHERE d.task_id = #{taskId} AND d.owner_id = #{ownerId} AND d.is_deleted = 0
              AND d.status IN ('UPLOADED', 'PROBING', 'READY_FOR_REVIEW', 'PROBE_FAILED')
            """;

    @Select("SELECT COUNT(*) " + COMPLETE_VERSIONS)
    long countVersions(@Param("taskId") String taskId, @Param("ownerId") String ownerId);

    @Select("""
            SELECT d.version_id, d.version_no, d.version_name, d.status, d.duration_ms,
                (d.media_deleted_at IS NULL) AS media_available,
                EXISTS(SELECT 1 FROM creator_video_version_context c WHERE c.version_id = d.version_id
                    AND c.task_id = d.task_id AND c.owner_id = d.owner_id) AS context_available,
                EXISTS(SELECT 1 FROM creator_preflight_review r
                    JOIN creator_media_processing_job j ON j.job_id = r.processing_job_id
                        AND j.version_id = d.version_id AND j.task_id = d.task_id AND j.owner_id = d.owner_id
                        AND j.status = 'COMPLETED' AND j.is_deleted = 0
                    WHERE r.review_id = d.current_review_id AND r.task_id = d.task_id AND r.owner_id = d.owner_id
                        AND r.version_id = d.version_id AND r.status = 'COMPLETED' AND r.is_deleted = 0
                        AND j.id = (SELECT MAX(j2.id) FROM creator_media_processing_job j2
                            WHERE j2.task_id = d.task_id AND j2.owner_id = d.owner_id
                                AND j2.version_id = d.version_id AND j2.is_deleted = 0)
                        AND EXISTS(SELECT 1 FROM creator_preflight_step x WHERE x.review_id = r.review_id
                            AND x.step_type = 'ANALYZE_VIDEO')) AS review_ready,
                (d.version_id = COALESCE(#{selectedVersionId}, '')) AS selected,
                (d.published_flag = 1 OR EXISTS(SELECT 1 FROM creator_task_video_binding b WHERE b.task_id = d.task_id
                    AND b.user_id = d.owner_id AND b.published_version_id = d.version_id
                    AND b.binding_status = 'BOUND' AND b.is_deleted = 0)) AS published,
                d.create_time
            """ + COMPLETE_VERSIONS + " ORDER BY d.version_no DESC, d.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<CreationVersionSummary> listVersions(@Param("taskId") String taskId, @Param("ownerId") String ownerId,
            @Param("selectedVersionId") String selectedVersionId, @Param("offset") long offset, @Param("limit") int limit);

    /** 切换前同时检查上传和媒体工作，不能让页面状态与仍在运行的任务脱节。 */
    @Select("""
            SELECT (SELECT COUNT(*) FROM creator_media_upload WHERE task_id = #{taskId} AND owner_id = #{ownerId}
                AND status IN ('CREATED', 'UPLOADING', 'VERIFYING') AND is_deleted = 0)
            + (SELECT COUNT(*) FROM creator_media_processing_job WHERE task_id = #{taskId} AND owner_id = #{ownerId}
                AND status IN ('QUEUED', 'RUNNING') AND is_deleted = 0)
            + (SELECT COUNT(*) FROM creator_preflight_review WHERE task_id = #{taskId} AND owner_id = #{ownerId}
                AND status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'CANCEL_REQUESTED') AND is_deleted = 0)
            + (SELECT COUNT(*) FROM creator_creation_run WHERE task_id = #{taskId} AND owner_id = #{ownerId}
                AND status IN ('QUEUED', 'RUNNING', 'WAITING_DEPENDENCY', 'CANCEL_REQUESTED'))
            """)
    int countActiveWork(@Param("taskId") String taskId, @Param("ownerId") String ownerId);
}
