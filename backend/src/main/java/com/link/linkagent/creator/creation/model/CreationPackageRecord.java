package com.link.linkagent.creator.creation.model;

/** 最小发布包事实；READY 表示对象已按本地摘要完成真实上传及 HEAD 校验。 */
public record CreationPackageRecord(String runId, String taskId, String ownerId, String versionId,
                                    String processingJobId, String preflightReviewId, String status,
                                    String bucketName, String objectKey, Long bytes, String sha256,
                                    String manifestJson, long leaseGeneration, String failureCode) {
}
