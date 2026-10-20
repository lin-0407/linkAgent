package com.link.linkagent.creator.creation.asset.mapper;

import com.link.linkagent.creator.creation.asset.model.CreationAsset;
import com.link.linkagent.creator.creation.asset.model.AssetRequests.Part;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Optional;

@Mapper
public interface CreationAssetMapper {
    String ACTIVE_TASK = "EXISTS(SELECT 1 FROM creator_task t WHERE t.task_id=a.task_id AND t.user_id=a.owner_id AND t.is_deleted=0)";

    @Select("SELECT task_id FROM creator_task WHERE task_id=#{taskId} AND user_id=#{ownerId} AND is_deleted=0 FOR UPDATE")
    Optional<String> lockTask(@Param("taskId") String taskId, @Param("ownerId") String ownerId);

    @Select("SELECT a.* FROM creator_creation_asset a WHERE a.asset_id=#{assetId} AND a.task_id=#{taskId} AND a.owner_id=#{ownerId} AND " + ACTIVE_TASK)
    Optional<CreationAsset> find(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("assetId") String assetId);

    @Select("SELECT * FROM creator_creation_asset WHERE asset_id=#{assetId} FOR UPDATE")
    CreationAsset lock(@Param("assetId") String assetId);

    @Select("SELECT * FROM creator_creation_asset WHERE task_id=#{taskId} AND owner_id=#{ownerId} AND idempotency_key=#{key}")
    Optional<CreationAsset> findKey(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("key") String key);

    @Select("SELECT a.* FROM creator_creation_asset a WHERE a.task_id=#{taskId} AND a.owner_id=#{ownerId} AND " + ACTIVE_TASK + " ORDER BY a.create_time DESC,a.asset_id LIMIT #{limit} OFFSET #{offset}")
    List<CreationAsset> list(@Param("taskId") String taskId, @Param("ownerId") String ownerId, @Param("offset") int offset, @Param("limit") int limit);

    @Insert("""
        INSERT INTO creator_creation_asset(asset_id,task_id,owner_id,idempotency_key,kind,file_name,content_type,
            expected_bytes,expected_sha256,bucket_name,source_key,status)
        VALUES(#{assetId},#{taskId},#{ownerId},#{idempotencyKey},#{kind},#{fileName},#{contentType},
            #{expectedBytes},#{expectedSha256},#{bucketName},#{sourceKey},'CREATING')
        """)
    int insert(CreationAsset asset);

    @Select("SELECT part_number,etag,part_size FROM creator_creation_asset_part WHERE asset_id=#{assetId} ORDER BY part_number")
    List<Part> parts(@Param("assetId") String assetId);

    @Insert("""
        INSERT INTO creator_creation_asset_part(asset_id,part_number,etag,part_size) VALUES(#{assetId},#{part.partNumber},#{part.etag},#{part.partSize}) AS incoming
        ON DUPLICATE KEY UPDATE etag=incoming.etag,part_size=incoming.part_size
        """)
    int savePart(@Param("assetId") String assetId, @Param("part") Part part);

    @Update("UPDATE creator_creation_asset SET status=#{next},failure_code=NULL,failure_message=NULL,failed_stage=NULL WHERE asset_id=#{assetId} AND status=#{expected} AND lease_token IS NULL")
    int transition(@Param("assetId") String assetId, @Param("expected") String expected, @Param("next") String next);

    @Select("""
        SELECT a.* FROM creator_creation_asset a WHERE
          ((a.status IN ('CREATING','COMPLETING','UPLOADED','PREPARING') AND (a.lease_token IS NULL OR a.lease_expires_at<=NOW(6)))
           OR (a.status='CANCELLED' AND (a.cleanup_pending=1 OR (a.cleanup_pending>0 AND a.lease_expires_at<=NOW(6))))) AND
        """ + ACTIVE_TASK + " ORDER BY a.update_time,a.asset_id LIMIT 10")
    List<CreationAsset> candidates();

    @Update("""
        UPDATE creator_creation_asset a SET status=#{phase},lease_token=#{token},lease_expires_at=TIMESTAMPADD(SECOND,300,NOW(6))
        WHERE asset_id=#{assetId} AND status=#{expected} AND (lease_token IS NULL OR lease_expires_at<=NOW(6)) AND
        """ + ACTIVE_TASK)
    int claim(@Param("assetId") String assetId, @Param("expected") String expected, @Param("phase") String phase, @Param("token") String token);

    @Update("UPDATE creator_creation_asset a SET lease_expires_at=TIMESTAMPADD(SECOND,300,NOW(6)) WHERE asset_id=#{assetId} AND lease_token=#{token} AND lease_expires_at>NOW(6) AND status IN ('CREATING','COMPLETING','PREPARING') AND " + ACTIVE_TASK)
    int heartbeat(@Param("assetId") String assetId, @Param("token") String token);

    @Update("""
        UPDATE creator_creation_asset a SET status=#{next},storage_upload_id=COALESCE(#{uploadId},storage_upload_id),
            source_sha256=COALESCE(#{sha256},source_sha256),metadata_json=COALESCE(#{metadata},metadata_json),
            objects_json=COALESCE(#{objects},objects_json),lease_token=NULL,lease_expires_at=NULL,failed_stage=NULL,failure_code=NULL,failure_message=NULL
        WHERE asset_id=#{assetId} AND lease_token=#{token} AND lease_expires_at>NOW(6)
          AND status IN ('CREATING','COMPLETING','PREPARING') AND
        """ + ACTIVE_TASK)
    int finish(@Param("assetId") String assetId, @Param("token") String token, @Param("next") String next,
            @Param("uploadId") String uploadId, @Param("sha256") String sha256, @Param("metadata") String metadata, @Param("objects") String objects);

    @Update("""
        UPDATE creator_creation_asset SET failed_stage=status,status='FAILED',failure_code=#{code},failure_message=#{message},lease_token=NULL,lease_expires_at=NULL
        WHERE asset_id=#{assetId} AND lease_token=#{token} AND lease_expires_at>NOW(6)
          AND status IN ('CREATING','COMPLETING','PREPARING')
        """)
    int failWithMessage(@Param("assetId") String assetId, @Param("token") String token, @Param("code") String code,@Param("message") String message);

    default int fail(String assetId,String token,String code) {return failWithMessage(assetId,token,code,"素材准备失败，请检查后重试");}

    @Update("UPDATE creator_creation_asset SET status='CANCELLED',cleanup_pending=1,lease_token=NULL,lease_expires_at=TIMESTAMPADD(SECOND,180,NOW(6)) WHERE asset_id=#{assetId} AND status NOT IN ('READY','CANCELLED')")
    int cancel(@Param("assetId") String assetId);

    @Update("UPDATE creator_creation_asset a SET cleanup_pending=0 WHERE asset_id=#{assetId} AND status='CANCELLED' AND lease_expires_at<=NOW(6) AND NOT EXISTS(SELECT 1 FROM creator_creation_asset_object o WHERE o.asset_id=a.asset_id AND o.cleanup_done=0)")
    int cleaned(@Param("assetId") String assetId);

    @Select("SELECT COUNT(*) FROM creator_creation_asset WHERE asset_id=#{assetId} AND status='CANCELLED' AND (cleanup_pending=1 OR (cleanup_pending>0 AND lease_expires_at<=NOW(6)))")
    int cleanupDue(@Param("assetId") String assetId);

    @Update("UPDATE creator_creation_asset SET cleanup_pending=2 WHERE asset_id=#{assetId} AND status='CANCELLED' AND cleanup_pending=1")
    int cleanupDeferred(@Param("assetId") String assetId);

    record PendingObject(String assetId,String leaseToken,String role,String objectKey,String bucketName) { }

    @Insert("""
        INSERT INTO creator_creation_asset_object(asset_id,lease_token,role,object_key,cleanup_after)
        SELECT asset_id,#{token},#{role},#{key},TIMESTAMPADD(SECOND,180,NOW(6)) FROM creator_creation_asset
        WHERE asset_id=#{assetId} AND lease_token=#{token} AND lease_expires_at>NOW(6) AND status='PREPARING'
        """)
    int registerObject(@Param("assetId") String assetId,@Param("token") String token,@Param("role") String role,@Param("key") String key);

    @Select("""
        SELECT o.asset_id,o.lease_token,o.role,o.object_key,a.bucket_name FROM creator_creation_asset_object o
        JOIN creator_creation_asset a ON a.asset_id=o.asset_id
        WHERE o.cleanup_done=0 AND o.cleanup_after<=NOW(6)
          AND NOT (a.status='PREPARING' AND a.lease_token=o.lease_token AND a.lease_expires_at>NOW(6))
          AND NOT (a.status='READY' AND JSON_CONTAINS(a.objects_json,JSON_OBJECT('objectKey',o.object_key)))
        ORDER BY o.create_time LIMIT 30
        """)
    List<PendingObject> pendingObjects();

    @Update("UPDATE creator_creation_asset_object SET cleanup_done=1 WHERE asset_id=#{assetId} AND lease_token=#{token} AND role=#{role} AND cleanup_after<=NOW(6)")
    int objectCleaned(@Param("assetId") String assetId,@Param("token") String token,@Param("role") String role);

    @Update("UPDATE creator_creation_asset_object SET cleanup_done=0,cleanup_after=NOW(6) WHERE asset_id=#{assetId} AND lease_token=#{token} AND role=#{role}")
    int objectUploaded(@Param("assetId") String assetId,@Param("token") String token,@Param("role") String role);
}
