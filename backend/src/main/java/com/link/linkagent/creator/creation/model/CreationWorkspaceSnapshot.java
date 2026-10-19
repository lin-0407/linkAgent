package com.link.linkagent.creator.creation.model;

import com.fasterxml.jackson.databind.JsonNode;

/** 所选版本的冻结资料是编辑基线，不覆盖任务当前候选资料。 */
public record CreationWorkspaceSnapshot(boolean enabled, long workspaceRevision,
        String currentVideoVersionId, String publishedVideoVersionId, JsonNode versionContext) {
}
