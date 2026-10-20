package com.link.linkagent.creator.creation.asset.controller;

import com.link.linkagent.creator.creation.asset.model.AssetRequests.*;
import com.link.linkagent.creator.creation.asset.service.CreationAssetService;
import com.link.linkagent.creator.media.storage.*;
import com.link.linkagent.creator.media.upload.model.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@Validated
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
@RequestMapping("/api/creator/tasks/{taskId}/assets")
public class CreationAssetController {
    private static final String ID="[A-Za-z0-9_-]{1,64}";
    private final CreationAssetService service;
    public CreationAssetController(CreationAssetService service) { this.service=service; }
    @PostMapping
    public Snapshot create(@PathVariable @Pattern(regexp=ID) String taskId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,@RequestBody @Valid Create request) {
        return service.create(taskId,"default",key,request);
    }
    @GetMapping
    public List<Snapshot> list(@PathVariable @Pattern(regexp=ID) String taskId,
            @RequestParam(defaultValue="0") @Min(0) @Max(100000) int offset,
            @RequestParam(defaultValue="30") @Min(1) @Max(100) int limit) { return service.list(taskId,"default",offset,limit); }
    @GetMapping("/{assetId}")
    public Snapshot get(@PathVariable @Pattern(regexp=ID) String taskId,@PathVariable @Pattern(regexp=ID) String assetId) { return service.get(taskId,"default",assetId); }
    @PostMapping("/{assetId}/parts:sign")
    public ResponseEntity<List<PresignedUploadPart>> sign(@PathVariable @Pattern(regexp=ID) String taskId,@PathVariable @Pattern(regexp=ID) String assetId,
            @RequestBody @Valid MediaUploadPartSignRequest request) { return privateResponse(service.sign(taskId,"default",assetId,request.partNumbers())); }
    @PostMapping("/{assetId}/parts:complete")
    public Snapshot parts(@PathVariable @Pattern(regexp=ID) String taskId,@PathVariable @Pattern(regexp=ID) String assetId,
            @RequestBody @Valid MediaUploadPartsCompleteRequest request) {return service.register(taskId,"default",assetId,request);}
    @PostMapping("/{assetId}:complete")
    public Snapshot complete(@PathVariable @Pattern(regexp=ID) String taskId,@PathVariable @Pattern(regexp=ID) String assetId) {return service.complete(taskId,"default",assetId);}
    @PostMapping("/{assetId}:retry")
    public Snapshot retry(@PathVariable @Pattern(regexp=ID) String taskId,@PathVariable @Pattern(regexp=ID) String assetId) {return service.retry(taskId,"default",assetId);}
    @DeleteMapping("/{assetId}")
    public Snapshot cancel(@PathVariable @Pattern(regexp=ID) String taskId,@PathVariable @Pattern(regexp=ID) String assetId) {return service.cancel(taskId,"default",assetId);}
    @PostMapping("/{assetId}:read-url")
    public ResponseEntity<PresignedObjectRead> read(@PathVariable @Pattern(regexp=ID) String taskId,@PathVariable @Pattern(regexp=ID) String assetId,
            @RequestParam(defaultValue="NORMALIZED") @Pattern(regexp="ORIGINAL|NORMALIZED|THUMBNAIL") String role) {return privateResponse(service.read(taskId,"default",assetId,role));}
    private <T> ResponseEntity<T> privateResponse(T body) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
