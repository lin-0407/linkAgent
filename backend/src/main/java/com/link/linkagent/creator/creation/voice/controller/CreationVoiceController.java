package com.link.linkagent.creator.creation.voice.controller;

import com.link.linkagent.creator.creation.voice.model.VoiceModels.*;
import com.link.linkagent.creator.creation.voice.service.CreationVoiceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 配音准备只开放估算、明确授权受理与状态查询；取消沿用运行入口。 */
@RestController
@Validated
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
@RequestMapping("/api/creator/tasks/{taskId}/creation")
public class CreationVoiceController {
    private static final String ID = "[A-Za-z0-9_-]{1,64}";
    private final CreationVoiceService service;

    public CreationVoiceController(CreationVoiceService service) { this.service = service; }

    @GetMapping("/storyboards/{storyboardId}/voice:estimate")
    public ResponseEntity<Estimate> estimate(@PathVariable @Pattern(regexp = ID) String taskId,
            @PathVariable @Pattern(regexp = ID) String storyboardId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.estimate(taskId, "default", storyboardId));
    }

    @PostMapping("/storyboards/{storyboardId}/voice")
    public ResponseEntity<Preparation> generate(@PathVariable @Pattern(regexp = ID) String taskId,
            @PathVariable @Pattern(regexp = ID) String storyboardId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @RequestBody @Valid Generate input) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(service.generate(taskId, "default", key, storyboardId, input));
    }

    @GetMapping("/voice-preparations/{runId}")
    public ResponseEntity<Preparation> preparation(@PathVariable @Pattern(regexp = ID) String taskId,
            @PathVariable @Pattern(regexp = ID) String runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.view(taskId, "default", runId));
    }
}
