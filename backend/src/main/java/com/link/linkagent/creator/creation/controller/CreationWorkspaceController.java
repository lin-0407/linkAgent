package com.link.linkagent.creator.creation.controller;

import com.link.linkagent.creator.creation.model.*;
import com.link.linkagent.creator.creation.service.CreationWorkspaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
@RequestMapping("/api/creator/tasks/{taskId}/creation")
public class CreationWorkspaceController {
    private static final String SAFE_ID = "^[A-Za-z0-9_-]{1,64}$";
    private final CreationWorkspaceService service;

    public CreationWorkspaceController(CreationWorkspaceService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<CreationWorkspaceSnapshot> snapshot(@PathVariable @Pattern(regexp = SAFE_ID) String taskId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.snapshot(taskId, "default"));
    }

    @GetMapping("/versions")
    public ResponseEntity<CreationVersionList> list(@PathVariable @Pattern(regexp = SAFE_ID) String taskId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(taskId, "default", page, pageSize));
    }

    @PostMapping("/versions/{versionId}:select")
    public CreationWorkspaceSnapshot select(@PathVariable @Pattern(regexp = SAFE_ID) String taskId,
            @PathVariable @Pattern(regexp = SAFE_ID) String versionId,
            @Valid @RequestBody SelectVideoVersionRequest request) {
        return service.select(taskId, "default", versionId, request.expectedWorkspaceRevision());
    }
}
