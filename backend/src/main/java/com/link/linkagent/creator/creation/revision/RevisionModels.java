package com.link.linkagent.creator.creation.revision;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public final class RevisionModels {
    private RevisionModels() { }
    public record Change(@NotBlank @Pattern(regexp="SET_NARRATION|SET_VISUAL|SET_CLIP_RANGE|SET_AUDIO|SET_BGM|SET_SUBTITLE_SIZE|MOVE_SCENE|SET_FIT")String operation,
            @Pattern(regexp="[A-Za-z0-9_-]{1,64}")String sceneId, JsonNode value) { }
    public record Propose(@NotNull @Min(0)Long expectedWorkspaceRevision,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}")String storyboardId,
            @Size(max=2000)String instruction,@Size(min=1,max=32)List<@NotNull @Valid Change> changes) { }
    public record Apply(@NotNull @Min(0)Long expectedWorkspaceRevision) { }
    public record Proposal(String proposalId,String taskId,String ownerId,String storyboardId,long expectedWorkspaceRevision,
            String idempotencyKey,String requestHash,String documentJson,String appliedStoryboardId) { }
    public record Difference(String sceneId,String field,JsonNode before,JsonNode after) { }
    public record Plan(List<String> voiceSceneIds,List<String> videoSceneIds,List<String> reusedAssetIds,
            boolean rebuildTimeline,BigDecimal estimatedPreparationCny,List<String> notes) { }
    public record View(String proposalId,String storyboardId,long workspaceRevision,List<Difference> differences,
            Plan plan,List<String> clarifications,String appliedStoryboardId) { }
}
