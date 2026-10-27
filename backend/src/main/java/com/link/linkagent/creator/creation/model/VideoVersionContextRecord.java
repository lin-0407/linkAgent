package com.link.linkagent.creator.creation.model;

/** 上传时验证并冻结的来源事实，后续候选不能改写旧视频的制作依据。 */
public record VideoVersionContextRecord(
        String versionId, String taskId, String ownerId, boolean planningSkipped,
        String publishingSessionId, String publishingResultId, String productionPlanId,
        String snapshotJson
) {
}
