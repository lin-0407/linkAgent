package com.link.linkagent.creator.creation.model;

/** 写入前必须复核活动运行和数据库时钟下的活租约。 */
public record GeneratedDraftLease(String taskId, String ownerId, String runId, String worker, long generation) { }
