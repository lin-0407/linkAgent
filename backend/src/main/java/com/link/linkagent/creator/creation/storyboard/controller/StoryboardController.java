package com.link.linkagent.creator.creation.storyboard.controller;

import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import com.link.linkagent.creator.creation.storyboard.mapper.StoryboardMapper;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardService;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardAudioService;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardAudioModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @Validated
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
@RequestMapping("/api/creator/tasks/{taskId}/creation")
public class StoryboardController {
    private static final String ID="[A-Za-z0-9_-]{1,64}";
    private final StoryboardService service;
    private final StoryboardAudioService audio;
    public StoryboardController(StoryboardService service,StoryboardAudioService audio){this.service=service;this.audio=audio;}
    @PostMapping("/briefs")
    public BriefView saveBrief(@PathVariable @Pattern(regexp=ID) String taskId,@RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,@RequestBody @Valid BriefInput input){return service.saveBrief(taskId,"default",key,input);}
    @GetMapping("/briefs")
    public List<BriefView> briefs(@PathVariable @Pattern(regexp=ID) String taskId,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int offset,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit){return service.briefs(taskId,"default",offset,limit);}
    @GetMapping("/briefs/{briefId}")
    public BriefView brief(@PathVariable @Pattern(regexp=ID) String taskId,@PathVariable @Pattern(regexp=ID) String briefId){return service.brief(taskId,"default",briefId);}
    @PostMapping("/storyboards")
    public BoardView save(@PathVariable @Pattern(regexp=ID) String taskId,@RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,@RequestBody @Valid Save input){return service.save(taskId,"default",key,input);}
    @GetMapping("/storyboards")
    public List<BoardView> boards(@PathVariable @Pattern(regexp=ID) String taskId,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int offset,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit){return service.boards(taskId,"default",offset,limit);}
    @GetMapping("/storyboards/{storyboardId}")
    public BoardView board(@PathVariable @Pattern(regexp=ID) String taskId,@PathVariable @Pattern(regexp=ID) String storyboardId){return service.board(taskId,"default",storyboardId);}
    @PostMapping("/storyboards/{storyboardId}/subtitles:import")
    public ResponseEntity<BoardView> importSubtitles(@PathVariable @Pattern(regexp=ID) String taskId,
            @PathVariable @Pattern(regexp=ID) String storyboardId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,@RequestBody @Valid SubtitleImport input){
        var saved=audio.importSubtitles(taskId,"default",key,storyboardId,input);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.board(taskId,"default",saved.storyboardId()));
    }
    @GetMapping("/storyboards/{storyboardId}/timing")
    public ResponseEntity<TimingReport> timing(@PathVariable @Pattern(regexp=ID) String taskId,
            @PathVariable @Pattern(regexp=ID) String storyboardId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(audio.timing(taskId,"default",storyboardId));
    }
    @PostMapping("/storyboards:generate")
    public ResponseEntity<Map<String,Object>> generate(@PathVariable @Pattern(regexp=ID) String taskId,@RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,@RequestBody @Valid Generate input){return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.generate(taskId,"default",key,input));}
    @PostMapping("/storyboards:estimate")
    public ResponseEntity<Map<String,Object>> estimate(@PathVariable @Pattern(regexp=ID) String taskId,@RequestBody @Valid EstimateInput input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.estimate(taskId,"default",input));}
    @GetMapping("/storyboard-preparations/{runId}")
    public ResponseEntity<Map<String,Object>> preparation(@PathVariable @Pattern(regexp=ID) String taskId,@PathVariable @Pattern(regexp=ID) String runId){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preparation(taskId,"default",runId));}
    @GetMapping("/asset-references/{assetId}")
    public List<StoryboardMapper.Reference> references(@PathVariable @Pattern(regexp=ID) String taskId,@PathVariable @Pattern(regexp=ID) String assetId,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int offset,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit){return service.references(taskId,"default",assetId,offset,limit);}
}
