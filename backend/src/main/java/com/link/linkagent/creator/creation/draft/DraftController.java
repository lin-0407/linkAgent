package com.link.linkagent.creator.creation.draft;

import com.link.linkagent.creator.creation.draft.DraftModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
@RequestMapping("/api/creator/tasks/{taskId}/creation")
public class DraftController {
    private static final String ID="[A-Za-z0-9_-]{1,64}";
    private final DraftService service;
    public DraftController(DraftService service){this.service=service;}
    @GetMapping("/drafts")
    public ResponseEntity<Workspace> workspace(@PathVariable @Pattern(regexp=ID)String taskId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.workspace(taskId,"default"));
    }
    @PostMapping("/drafts")
    public ResponseEntity<View> start(@PathVariable @Pattern(regexp=ID)String taskId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128)String key,@RequestBody @Valid Start input) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.start(taskId,"default",key,input));
    }
    @PostMapping("/drafts/{id}:cancel")
    public ResponseEntity<View> cancel(@PathVariable @Pattern(regexp=ID)String taskId,@PathVariable @Pattern(regexp=ID)String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.cancel(taskId,"default",id));
    }
}
