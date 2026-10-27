package com.link.linkagent.creator.creation.storyboard.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.BoardView;

/** 音频建议只描述已有媒体事实；接受建议必须另存用户明确选择的新版本。 */
public final class StoryboardAudioModels {
    private StoryboardAudioModels() { }

    public record SubtitleImport(@NotNull @Min(0) Long expectedWorkspaceRevision,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String sceneId,
            @NotBlank @Size(max=65536) String srt) { }

    public record TimingProposal(String sceneId, String code, String message, Long proposedDurationMs) { }

    public record SceneTiming(String sceneId, String audioMode, long sceneDurationMs,
            Long audioDurationMs, Long differenceMs, String timingSource, List<TimingProposal> proposals) { }

    public record TimingReport(String storyboardId, String briefId, long targetDurationMs,
            long quantizedDurationMs, long differenceMs, List<SceneTiming> scenes,
            List<TimingProposal> proposals, boolean requiresUserDecision) { }

    /** 只接受时长提案原值；用户不能凭任意毫秒数改写场景，也不能靠重复场景绕过合计校验。 */
    public record TimelineAdjustment(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String sceneId,
            @NotBlank @Pattern(regexp="EXTEND_SCENE|SHORTEN_SCENE") String proposalCode,
            @Min(1) @Max(30000) long durationMs) { }

    public record TimelineApply(@NotNull @Min(0) Long expectedWorkspaceRevision,
            @NotNull @Size(min=1,max=48) List<@NotNull @Valid TimelineAdjustment> adjustments) { }

    public record AppliedAdjustment(String sceneId, String proposalCode, long previousDurationMs, long durationMs) { }

    /** 采纳会创建新简报版本，因为总时长是用户意图，旧简报原文必须保留。 */
    public record TimelineApplyResult(BoardView board, int briefRevision, long targetDurationMs,
            List<AppliedAdjustment> applied, List<String> notes) { }
}
