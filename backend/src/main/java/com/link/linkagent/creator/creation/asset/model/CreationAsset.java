package com.link.linkagent.creator.creation.asset.model;

import lombok.Data;
import java.time.LocalDateTime;

/** 素材有独立身份，上传和规范化均不能改变成片版本及当前视频指针。 */
@Data
public class CreationAsset {
    private String assetId;
    private String taskId;
    private String ownerId;
    private String idempotencyKey;
    private String kind;
    private String fileName;
    private String contentType;
    private long expectedBytes;
    private String expectedSha256;
    private String bucketName;
    private String sourceKey;
    private String storageUploadId;
    private String status;
    private String failedStage;
    private String failureCode;
    private String failureMessage;
    private String leaseToken;
    private LocalDateTime leaseExpiresAt;
    private String sourceSha256;
    private String metadataJson;
    private String objectsJson;
    private boolean cleanupPending;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
