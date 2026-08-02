package com.link.linkagent.creator.bilibili.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * B站公开视频同步脚本的完整输出。
 * 列表字段在构造时统一为空列表，避免外部接口缺字段时把 null 传播到业务层。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BilibiliVideoSyncPayload(
        String bilibiliUid,
        String nickname,
        String avatarUrl,
        boolean hasMore,
        boolean partial,
        List<BilibiliVideoSyncItem> videos,
        List<BilibiliVideoVerificationResult> verificationResults,
        List<String> warnings
) {

    public BilibiliVideoSyncPayload {
        videos = videos == null ? List.of() : List.copyOf(videos);
        verificationResults = verificationResults == null ? List.of() : List.copyOf(verificationResults);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
