package com.link.linkagent.creator.creation.model;

/** 整片运行进入媒体处理与完整试映的唯一闭环；子任务标识一旦落库只恢复不重建。 */
public record CreationMediaLink(String runId, String taskId, String ownerId, String versionId,
                                String processingJobId, String preflightReviewId, String stage,
                                long leaseGeneration, String failureCode) {
}
