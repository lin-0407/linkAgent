package com.link.linkagent.creator.creation.execution;

import com.link.linkagent.creator.creation.execution.ExecutionModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @Validated
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
@RequestMapping("/api/creator/tasks/{taskId}/creation")
public class ExecutionController {
    private static final String ID="[A-Za-z0-9_-]{1,64}";
    private final ExecutionService service;
    private final ExecutionDownloads downloads;
    public ExecutionController(ExecutionService service,ExecutionDownloads downloads){this.service=service;this.downloads=downloads;}
    @PostMapping("/executions:estimate")
    public ResponseEntity<Estimate> estimate(@PathVariable @Pattern(regexp=ID)String taskId,@RequestBody @Valid EstimateInput input) {
        return noStore(service.estimate(taskId,"default",input));
    }
    @PostMapping("/executions")
    public ResponseEntity<View> start(@PathVariable @Pattern(regexp=ID)String taskId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128)String key,@RequestBody @Valid Start input) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.start(taskId,"default",key,input));
    }
    @GetMapping("/executions")
    public ResponseEntity<List<View>> list(@PathVariable @Pattern(regexp=ID)String taskId){return noStore(service.list(taskId,"default"));}
    @GetMapping("/executions/{id}")
    public ResponseEntity<View> get(@PathVariable @Pattern(regexp=ID)String taskId,@PathVariable @Pattern(regexp=ID)String id){return noStore(service.get(taskId,"default",id));}
    @PostMapping("/executions/{id}:cancel")
    public ResponseEntity<View> cancel(@PathVariable @Pattern(regexp=ID)String taskId,@PathVariable @Pattern(regexp=ID)String id){return noStore(service.cancel(taskId,"default",id));}
    @GetMapping("/executions/{id}/downloads/{fileName}")
    public ResponseEntity<ExecutionDownloads.Download> download(@PathVariable @Pattern(regexp=ID)String taskId,
            @PathVariable @Pattern(regexp=ID)String id,@PathVariable String fileName){return noStore(downloads.download(taskId,"default",id,fileName));}
    private static <T> ResponseEntity<T> noStore(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
