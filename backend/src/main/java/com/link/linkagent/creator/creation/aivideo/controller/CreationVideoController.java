package com.link.linkagent.creator.creation.aivideo.controller;

import com.link.linkagent.creator.creation.aivideo.model.VideoModels.*;
import com.link.linkagent.creator.creation.aivideo.service.CreationVideoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** AI 视频生成只开放能力查询、估算、明确授权受理与状态查询；取消沿用运行入口。 */
@RestController
@Validated
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
@RequestMapping("/api/creator/tasks/{taskId}/creation")
public class CreationVideoController {
    private static final String ID = "[A-Za-z0-9_-]{1,64}";
    private final CreationVideoService service;

    public CreationVideoController(CreationVideoService service) { this.service = service; }

    @GetMapping("/ai-video:capability")
    public ResponseEntity<Capability> capability(@PathVariable @Pattern(regexp = ID) String taskId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.capability());
    }

    @PostMapping("/storyboards/{storyboardId}/ai-video:estimate")
    public ResponseEntity<Estimate> estimate(@PathVariable @Pattern(regexp = ID) String taskId,
            @PathVariable @Pattern(regexp = ID) String storyboardId,
            @RequestBody @Valid EstimateInput input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.estimate(taskId, "default", storyboardId, input.scope(), input.regenerateAll()));
    }

    @PostMapping("/storyboards/{storyboardId}/ai-video")
    public ResponseEntity<Preparation> generate(@PathVariable @Pattern(regexp = ID) String taskId,
            @PathVariable @Pattern(regexp = ID) String storyboardId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @RequestBody @Valid Generate input) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(service.generate(taskId, "default", key, storyboardId, input));
    }

    @GetMapping("/ai-video-preparations/{runId}")
    public ResponseEntity<Preparation> preparation(@PathVariable @Pattern(regexp = ID) String taskId,
            @PathVariable @Pattern(regexp = ID) String runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.view(taskId, "default", runId));
    }
}
