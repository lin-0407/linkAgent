package com.link.linkagent.creator.creation.controller;

import com.link.linkagent.creator.creation.model.CreationRuntimeModels.*;
import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 执行器未接入前只开放预算与已有运行管理，避免页面出现无人消费的排队任务。 */
@Validated
@RestController
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
@RequestMapping("/api/creator/tasks/{taskId}/creation")
public class CreationRuntimeController {
    private static final String ID = "^[A-Za-z0-9_-]{1,64}$";
    private final CreationRuntimeService service;
    public CreationRuntimeController(CreationRuntimeService service) { this.service = service; }

    @PostMapping
    public Snapshot initialize(@PathVariable @Pattern(regexp = ID) String taskId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @RequestBody @Valid InitializeBudgetRequest request) {
        return service.initialize(taskId, "default", key, request.expectedWorkspaceRevision(), request.budgetLimitCny());
    }
    @GetMapping("/runtime")
    public ResponseEntity<Snapshot> snapshot(@PathVariable @Pattern(regexp = ID) String taskId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.snapshot(taskId, "default"));
    }
    @PatchMapping("/budget")
    public Snapshot budget(@PathVariable @Pattern(regexp = ID) String taskId, @RequestBody @Valid UpdateBudgetRequest request) {
        return service.updateBudget(taskId, "default", request.expectedWorkspaceRevision(), request.budgetLimitCny());
    }
    @GetMapping("/runs/{runId}")
    public ResponseEntity<RunView> run(@PathVariable @Pattern(regexp = ID) String taskId,
            @PathVariable @Pattern(regexp = ID) String runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.getRun(taskId, "default", runId));
    }
    @PostMapping("/runs/{runId}:cancel")
    public RunView cancel(@PathVariable @Pattern(regexp = ID) String taskId, @PathVariable @Pattern(regexp = ID) String runId) {
        return service.cancel(taskId, "default", runId);
    }
}
