package com.link.linkagent.creator.creation.voice.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

/** 配音准备只接受用户已保存的故事板和明确金额；文案来自不可变故事板，不能由请求改写。 */
public final class VoiceModels {
    private VoiceModels() { }

    public record Generate(@NotNull @Min(0) Long expectedWorkspaceRevision,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal maxAdditionalCostCny) { }

    public record Proposal(String sceneId, String code, String message, Long proposedDurationMs) { }

    public record SceneEstimate(String sceneId, int characters, String textSha256, long sceneDurationMs,
            BigDecimal ttsUsdUpperBound, BigDecimal asrUsdUpperBound) { }

    /** 估值只说明配置价格下的保守上界，不承诺供应商账单，也不代表已获得执行授权。 */
    public record Estimate(String storyboardId, String briefId, boolean configured, String model, String voice,
            String asrModel, int sceneCount, long totalCharacters, BigDecimal usdToCny,
            BigDecimal estimatedMaxAdditionalCostCny, String basis, List<SceneEstimate> scenes, List<String> blockers) { }

    public record SceneView(String sceneId, int position, String status, int characters, long sceneDurationMs,
            String audioAssetId, Long audioDurationMs, String audioSha256, BigDecimal matchRatio,
            String errorCode, String expectedText, String transcriptText, List<Proposal> proposals) { }

    public record Preparation(String runId, String runStatus, String runStage, String sourceStoryboardId,
            String storyboardId, boolean requiresUserDecision, List<SceneView> scenes, List<Proposal> proposals) { }

    /** 保存新故事板时返回的摘要，供页面继续读取完整 BoardView。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BoardResult(String storyboardId, String sourceStoryboardId, int sceneCount, int cueCount) { }
}
