package com.link.linkagent.creator.creation.model;

/** 内部固定工程受理依据；发布确认必须已经存在，不由此请求伪造。 */
public record AutomaticProductionRequest(String publishingSessionId, String publishingResultId, String baseVersionId,
        String storyboardId, String renderContentHash, long targetDurationMs) { }
