package com.link.linkagent.creator.creation.model;

/** 固定工程的持久化执行输入；源资产位于部署者配置的工作目录，不接收浏览器路径。 */
public record FixedProjectJob(String runId, String taskId, String ownerId, String sourceAttemptId,
                              String projectJson) { }
