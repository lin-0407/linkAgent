package com.link.linkagent.creator.creation.storyboard.model;

import jakarta.validation.constraints.*;
import java.util.List;

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
}
