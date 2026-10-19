package com.link.linkagent.creator.creation.model;

/** 当前成片使用明确指针，避免把版本序号误当成用户选择。 */
public record CreationWorkspaceRecord(
        String taskId,
        String ownerId,
        long workspaceRevision,
        String currentVideoVersionId,
        int nextVersionNo
) {
}
