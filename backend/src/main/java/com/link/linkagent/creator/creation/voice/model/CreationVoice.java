package com.link.linkagent.creator.creation.voice.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 一条场景配音事实；供应商参数与实际音频摘要分开保存，改词后不能沿用旧音频。 */
@Data
public class CreationVoice {
    private String runId;
    private String sceneId;
    private String taskId;
    private String ownerId;
    private String briefId;
    private String storyboardId;
    private int position;
    private String status;
    private String textContent;
    private String textSha256;
    private int characters;
    private long sceneDurationMs;
    private String provider;
    private String model;
    private String voiceId;
    private String providerRequestId;
    private String providerTaskId;
    private String audioAssetId;
    private String audioSha256;
    private Long audioBytes;
    private Long audioDurationMs;
    private String transcriptJson;
    private BigDecimal matchRatio;
    private String proposalJson;
    private String errorCode;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
