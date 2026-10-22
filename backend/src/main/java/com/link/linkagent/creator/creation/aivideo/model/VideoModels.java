package com.link.linkagent.creator.creation.aivideo.model;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

/** AI 视频生成只接受镜头范围、提示词与授权额度，不接受路径、命令或供应商参数。 */
public final class VideoModels {
    private VideoModels() { }

    public record EstimateInput(@NotNull @Min(0) Long expectedWorkspaceRevision,
            @NotBlank @Pattern(regexp = "SELECTED|ALL") String scope,
            Boolean regenerate) {
        public boolean regenerateAll() { return Boolean.TRUE.equals(regenerate); }
    }

    public record Generate(@NotNull @Min(0) Long expectedWorkspaceRevision,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal maxAdditionalCostCny,
            @NotBlank @Pattern(regexp = "SELECTED|ALL") String scope,
            Boolean regenerate) {
        public boolean regenerateAll() { return Boolean.TRUE.equals(regenerate); }
    }

    /** 能力入口只返回脱敏状态，不返回服务地址、密钥或供应商原始能力。 */
    public record Capability(boolean configured, String provider, String model, List<Integer> durationsSeconds,
            int width, int height, BigDecimal usdPerSecond, BigDecimal usdToCny, String priceBasis,
            String protocolVersion, List<String> blockers) { }

    /** action 只有 GENERATE / REUSE / BLOCKED；switchesToAi 表示该镜头会从当前画面来源切换为 AI。 */
    public record ScenePlan(String sceneId, String layout, String action, boolean switchesToAi,
            boolean bodyCleared, long durationMs, Integer requestedSeconds, String prompt, String promptSha256,
            String reuseAssetId, BigDecimal estimatedUsd, BigDecimal estimatedCny, List<String> blockers) { }

    public record Estimate(String storyboardId, String briefId, String scope, boolean configured,
            boolean regenerate, String provider, String model, List<Integer> durationsSeconds,
            int width, int height, BigDecimal usdPerSecond, BigDecimal usdToCny, String priceBasis,
            int sceneCount, int generateCount, int reuseCount, int switchCount,
            BigDecimal estimatedMaxCostCny, List<ScenePlan> scenes, List<String> blockers) { }

    public record Proposal(String sceneId, String code, String message, Long proposedDurationMs) { }

    public record SceneView(String sceneId, int position, String status, String prompt, String promptSha256,
            long sceneDurationMs, Integer requestedSeconds, String videoAssetId, Long videoDurationMs,
            Integer width, Integer height, String providerTaskId, String errorCode, List<Proposal> proposals) { }

    public record Preparation(String runId, String status, String stage, String storyboardId,
            String storyboardResultId, boolean requiresUserDecision, List<SceneView> scenes,
            List<Proposal> proposals) { }
}
