package com.link.linkagent.creator.creation.mapper;

import com.link.linkagent.creator.creation.model.CreationPackageRecord;
import org.apache.ibatis.annotations.*;

import java.util.Optional;

@Mapper
public interface CreationPackageMapper {
    String COLUMNS = "run_id, task_id, owner_id, version_id, processing_job_id, preflight_review_id, status, "
            + "bucket_name, object_key, bytes, sha256, manifest_json, lease_generation, failure_code";

    @Select("SELECT " + COLUMNS + " FROM creator_creation_package WHERE run_id=#{runId}")
    Optional<CreationPackageRecord> find(@Param("runId") String runId);

    /**
     * 已 READY 的包不因重复扫描重建；只有未完成的构建才允许新代数接手。
     * 这样恢复和并发扫描都不会重复上传同一版本。使用行别名而不是已弃用的 VALUES()。
     */
    @Insert("""
            INSERT INTO creator_creation_package (run_id, task_id, owner_id, version_id, processing_job_id,
                preflight_review_id, status, manifest_json, lease_generation)
            VALUES (#{runId}, #{taskId}, #{ownerId}, #{versionId}, #{processingJobId}, #{preflightReviewId},
                'BUILDING', '{}', #{leaseGeneration}) AS fresh
            ON DUPLICATE KEY UPDATE
                status=IF(creator_creation_package.status='READY','READY','BUILDING'),
                failure_code=IF(creator_creation_package.status='READY',creator_creation_package.failure_code,NULL),
                lease_generation=IF(creator_creation_package.status='READY',creator_creation_package.lease_generation,fresh.lease_generation)
            """)
    int begin(CreationPackageRecord value);

    @Update("""
            UPDATE creator_creation_package SET status='READY', bucket_name=#{bucketName}, object_key=#{objectKey},
                bytes=#{bytes}, sha256=#{sha256}, manifest_json=#{manifestJson}, failure_code=NULL
            WHERE run_id=#{runId} AND status='BUILDING' AND lease_generation=#{leaseGeneration}
            """)
    int ready(CreationPackageRecord value);

    @Update("""
            UPDATE creator_creation_package SET status='FAILED', failure_code=#{failureCode}
            WHERE run_id=#{runId} AND status='BUILDING' AND lease_generation=#{leaseGeneration}
            """)
    int fail(@Param("runId") String runId, @Param("leaseGeneration") long leaseGeneration,
             @Param("failureCode") String failureCode);
}
