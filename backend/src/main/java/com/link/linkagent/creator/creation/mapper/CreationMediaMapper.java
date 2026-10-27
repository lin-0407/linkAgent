package com.link.linkagent.creator.creation.mapper;

import com.link.linkagent.creator.creation.model.CreationMediaLink;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Optional;

@Mapper
public interface CreationMediaMapper {
    // 列顺序必须与 CreationMediaLink 的构造参数一致：MyBatis 按位置装配 record。
    String COLUMNS = "run_id, task_id, owner_id, version_id, processing_job_id, preflight_review_id, stage, lease_generation, failure_code";

    @Select("SELECT " + COLUMNS + " FROM creator_creation_media_link WHERE run_id=#{runId}")
    Optional<CreationMediaLink> find(@Param("runId") String runId);

    @Select("SELECT " + COLUMNS + " FROM creator_creation_media_link WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND version_id=#{versionId}")
    Optional<CreationMediaLink> byVersion(@Param("taskId") String taskId, @Param("ownerId") String ownerId,
                                          @Param("versionId") String versionId);

    @Select("SELECT " + COLUMNS + " FROM creator_creation_media_link WHERE preflight_review_id=#{reviewId}")
    Optional<CreationMediaLink> byReview(@Param("reviewId") String reviewId);

    @Insert("""
            INSERT IGNORE INTO creator_creation_media_link (run_id, task_id, owner_id, version_id, stage, lease_generation)
            VALUES (#{runId}, #{taskId}, #{ownerId}, #{versionId}, 'MEDIA_PENDING', #{leaseGeneration})
            """)
    int insert(CreationMediaLink value);

    /** 只允许从当前阶段前进一步，重复扫描或旧执行者不能把已推进的闭环改回去。 */
    @Update("""
            UPDATE creator_creation_media_link l JOIN creator_creation_run r ON r.run_id=l.run_id
                AND r.task_id=l.task_id AND r.owner_id=l.owner_id
            SET l.stage=#{stage}, l.processing_job_id=#{jobId}, l.preflight_review_id=#{reviewId},
                l.failure_code=#{failureCode}, l.lease_generation=#{generation}
            WHERE l.run_id=#{runId} AND l.stage=#{expected} AND r.status='RUNNING'
                AND r.lease_generation=#{generation} AND r.lease_expires_at>CURRENT_TIMESTAMP(6)
            """)
    int advance(@Param("runId") String runId, @Param("expected") String expected, @Param("stage") String stage,
                @Param("jobId") String jobId, @Param("reviewId") String reviewId,
                @Param("failureCode") String failureCode, @Param("generation") long generation);

    @Select("""
            SELECT l.run_id, l.task_id, l.owner_id, l.version_id, l.processing_job_id,
                l.preflight_review_id, l.stage, l.lease_generation, l.failure_code
            FROM creator_creation_media_link l
            JOIN creator_creation_run r ON r.run_id=l.run_id AND r.task_id=l.task_id AND r.owner_id=l.owner_id
            JOIN creator_creation_workspace w ON w.task_id=r.task_id AND w.owner_id=r.owner_id
                AND w.active_run_id=r.run_id AND w.is_deleted=0
            JOIN creator_task t ON t.task_id=r.task_id AND t.user_id=r.owner_id AND t.is_deleted=0
            WHERE (r.status='CANCEL_REQUESTED' AND l.stage NOT IN ('COMPLETED','CANCELLED'))
                OR (l.stage IN ('MEDIA_PENDING','MEDIA_RUNNING','PREFLIGHT_RUNNING','PACKAGE_PENDING','PACKAGE_READY')
                    AND (r.status IN ('WAITING_DEPENDENCY','QUEUED') OR (r.status='RUNNING'
                        AND (r.lease_expires_at IS NULL OR r.lease_expires_at<=CURRENT_TIMESTAMP(6)))))
            ORDER BY l.update_time LIMIT 20
            """)
    List<CreationMediaLink> candidates();

    /** 运行结束或取消时收口闭环；已完成的事实不能被后来者改写。 */
    @Update("""
            UPDATE creator_creation_media_link SET stage=IF(stage='COMPLETED','COMPLETED','CANCELLED')
            WHERE run_id=#{runId} AND stage NOT IN ('COMPLETED','CANCELLED')
            """)
    int close(@Param("runId") String runId);

    /** 只有发布包已 READY 的整片运行才会走到这里，阶段由同一终态事务收口。 */
    @Update("""
            UPDATE creator_creation_media_link SET stage='COMPLETED', failure_code=NULL
            WHERE run_id=#{runId} AND stage IN ('PACKAGE_READY','PACKAGE_PENDING')
            """)
    int complete(@Param("runId") String runId);
}
