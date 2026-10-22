package com.link.linkagent.creator.creation.storyboard.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 业务工程只引用素材身份和受控字段，不允许模型提交路径、命令或字幕时间。 */
public final class StoryboardModels {
    private StoryboardModels() { }
    public record BriefInput(@NotNull @Min(0) Long expectedWorkspaceRevision,
            @NotBlank @Size(max=200) String topic,@NotBlank @Size(max=500) String coreMessage,
            @NotBlank @Size(max=200) String audience,@Min(1000) @Max(180000) long targetDurationMs,
            @NotBlank @Pattern(regexp="TTS|USER_NARRATION|SOURCE|NO_DIALOGUE") String dialogueMode,
            @NotBlank @Pattern(regexp="USER_PROVIDED|FICTION") String contentKind,
            @NotNull @Size(max=32) List<@NotNull @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String> assetIds,
            @NotNull @Size(max=2000) String constraints,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @Valid BackgroundMusic backgroundMusic,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @Valid Ambience ambience) {
        public BriefInput(Long expectedWorkspaceRevision,String topic,String coreMessage,String audience,long targetDurationMs,
                String dialogueMode,String contentKind,List<String> assetIds,String constraints) {
            this(expectedWorkspaceRevision,topic,coreMessage,audience,targetDurationMs,dialogueMode,contentKind,assetIds,constraints,null,null);
        }
        public BriefInput(Long expectedWorkspaceRevision,String topic,String coreMessage,String audience,long targetDurationMs,
                String dialogueMode,String contentKind,List<String> assetIds,String constraints,BackgroundMusic backgroundMusic) {
            this(expectedWorkspaceRevision,topic,coreMessage,audience,targetDurationMs,dialogueMode,contentKind,assetIds,constraints,backgroundMusic,null);
        }
    }
    public record BackgroundMusic(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String assetId,
            @Min(0) long sourceInMs,@Min(1) @Max(1200000) long sourceOutMs,
            @NotNull @DecimalMin("-36") @DecimalMax("0") @Digits(integer=2,fraction=1) BigDecimal gainDb,
            @Min(0) @Max(10000) int fadeInMs,@Min(0) @Max(10000) int fadeOutMs,
            @NotNull Boolean ducking) { }
    /** 环境声与背景音乐分开：独立音量、固定不压低，用于铺底的空间感而不是配乐。 */
    public record Ambience(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String assetId,
            @Min(0) long sourceInMs,@Min(1) @Max(1200000) long sourceOutMs,
            @NotNull @DecimalMin("-36") @DecimalMax("0") @Digits(integer=2,fraction=1) BigDecimal gainDb,
            @Min(0) @Max(10000) int fadeInMs,@Min(0) @Max(10000) int fadeOutMs) { }
    public record Brief(String briefId,String taskId,String ownerId,int revision,String idempotencyKey,
            String requestHash,String documentJson,String sourceJson,LocalDateTime createTime) { }
    public record Cue(@Min(0) long startMs,@Min(1) long endMs,@NotBlank @Size(max=120) String text) { }
    public record Scene(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String id,
            @NotBlank @Pattern(regexp="TITLE_CARD|IMAGE_CARD|SPLIT_CARD|COMPARE_CARD|VIDEO_CLIP|AI_VIDEO") String layout,
            @NotBlank @Size(max=60) String title,@NotNull @Size(max=4) List<@NotBlank @Size(max=90) String> body,
            @NotNull @Size(max=1000) String narration,@Min(1) @Max(30000) long durationMs,
            @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String visualAssetId,
            @Min(0) Long sourceInMs,@Min(1) Long sourceOutMs,
            @NotBlank @Pattern(regexp="CONTAIN|COVER") String fit,
            @NotBlank @Pattern(regexp="NO_DIALOGUE|ORIGINAL|NARRATION|TTS") String audioMode,
            @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String narrationAssetId,
            @NotNull @Size(max=100) List<@NotNull @Valid Cue> cues,
            @NotNull @Size(max=16) List<@NotBlank @Size(max=128) String> sourceRefs) { }
    public record Candidate(@NotNull @Size(min=1,max=48) List<@NotNull @Valid Scene> scenes) { }
    public record Save(@NotNull @Min(0) Long expectedWorkspaceRevision,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String briefId,
            @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String parentStoryboardId,
            @NotNull @Valid Candidate candidate) { }
    public record Generate(@NotNull @Min(0) Long expectedWorkspaceRevision,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String briefId,
            @NotNull @DecimalMin("0.000001") @Digits(integer=12,fraction=6) BigDecimal maxAdditionalCostCny) { }
    public record EstimateInput(@NotNull @Min(0) Long expectedWorkspaceRevision,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String briefId) { }
    public record Board(String storyboardId,String taskId,String ownerId,int revision,String briefId,
            String parentStoryboardId,String runId,String idempotencyKey,String requestHash,String documentHash,
            String documentJson,String assetSnapshotJson,LocalDateTime createTime) { }
    public record Job(String runId,String taskId,String ownerId,String briefId,String requestHash,String storyboardId) { }
    public record Asset(String assetId,String kind,String bucketName,String objectKey,String sha256,long bytes,
            String contentType,JsonNode metadata) { }
    public record Problem(String sceneId,String code,String message) { }
    public record Check(boolean renderable,List<Problem> problems,long durationMs,String renderContentHash) { }
    public record BriefView(String briefId,int revision,JsonNode document,JsonNode sources) { }
    public record BoardView(String storyboardId,int revision,String briefId,String parentStoryboardId,String runId,
            String documentHash,JsonNode document,Check check) { }
}
