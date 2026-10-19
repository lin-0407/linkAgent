package com.link.linkagent.creator.creation.model;

import java.util.List;

public record CreationVersionList(long total, int page, int pageSize, long workspaceRevision, boolean replacementAllowed,
                                 List<CreationVersionSummary> versions) {
}
