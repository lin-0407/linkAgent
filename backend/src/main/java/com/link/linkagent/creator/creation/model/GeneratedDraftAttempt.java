package com.link.linkagent.creator.creation.model;

/** 上传前保存对象清单，重试和删除不需要猜测文件或对象键。 */
public record GeneratedDraftAttempt(String runId, String attemptId, String taskId, String ownerId,
        long leaseGeneration, String versionId, String productionPlanId, String inputHash,
        String bundleHash, String bucketName, String objectsJson, String status) { }
