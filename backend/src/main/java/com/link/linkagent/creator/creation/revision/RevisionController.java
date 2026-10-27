package com.link.linkagent.creator.creation.revision;

import com.link.linkagent.creator.creation.revision.RevisionModels.*;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.BoardView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
@RequestMapping("/api/creator/tasks/{taskId}/creation")
public class RevisionController {
    private static final String ID="[A-Za-z0-9_-]{1,64}";
    private final RevisionService service;
    public RevisionController(RevisionService service){this.service=service;}
    @PostMapping("/revisions:propose")
    public ResponseEntity<View> propose(@PathVariable @Pattern(regexp=ID)String taskId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128)String key,@RequestBody @Valid Propose input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.propose(taskId,"default",key,input));}
    @PostMapping("/revisions/{id}:apply")
    public ResponseEntity<BoardView> apply(@PathVariable @Pattern(regexp=ID)String taskId,
            @PathVariable @Pattern(regexp=ID)String id,@RequestBody @Valid Apply input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.apply(taskId,"default",id,input));}
}
