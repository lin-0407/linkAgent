package com.link.linkagent.creator.creation.aivideo.model;

import lombok.Data;

import java.time.LocalDateTime;

/** AI 视频场景事实：提示词、请求时长、异步任务标识与真实片段结果逐行保存。 */
@Data
public class CreationVideo {
    private String runId;
    private String sceneId;
    private String taskId;
    private String ownerId;
    private String briefId;
    private String storyboardId;
    private int position;
    private String status;
    private String promptText;
    private String promptSha256;
    private long sceneDurationMs;
    private int requestedSeconds;
    private String provider;
    private String model;
    private String providerTaskId;
    private String providerRequestId;
    private String videoAssetId;
    private String videoSha256;
    private Long videoBytes;
    private Long videoDurationMs;
    private Integer videoWidth;
    private Integer videoHeight;
    private String proposalJson;
    private String errorCode;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
