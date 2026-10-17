package com.link.linkagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.assistant.AssistantPlanModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

/** 这些确认接口不注册成模型工具，只有用户界面能够启动已审阅的计划。 */
@Validated
@RestController
@RequestMapping("/api/assistant")
public class AssistantPlanController {
    private final AssistantPlanService plans;
    private final PlatformOperationCatalog catalog;
    private final ObjectMapper json;
    public AssistantPlanController(AssistantPlanService plans,PlatformOperationCatalog catalog,ObjectMapper json) { this.plans=plans; this.catalog=catalog; this.json=json; }
    @GetMapping("/operations") public Object operations(@RequestParam(defaultValue="") @Size(max=100) String query) { return query.isBlank()?catalog.index():catalog.discover(query); }
    @GetMapping("/plans") public List<View> list() { return plans.recent(); }
    @GetMapping("/plans/{id}") public View get(@PathVariable @NotBlank @Size(max=64) String id) { return plans.get(id); }
    @PostMapping("/plans") public View prepare(@Valid @RequestBody Prepare request) { return plans.prepare(request); }
    @PostMapping("/plans/{id}/confirm") public View confirm(@PathVariable @NotBlank @Size(max=64) String id,@Valid @RequestBody Approval request) { return plans.confirm(id,request); }
    @PostMapping("/plans/{id}/inputs") public View input(@PathVariable @NotBlank @Size(max=64) String id,@Valid @RequestBody Inputs request) { return plans.inputs(id,request,List.of()); }
    @PostMapping(value="/plans/{id}/files",consumes="multipart/form-data")
    public View files(@PathVariable @NotBlank @Size(max=64) String id,@RequestParam @NotBlank @Size(max=64) String digest,
                      @RequestParam @AssertTrue boolean confirmed,@RequestParam(defaultValue="{}") @Size(max=100000) String values,
                      @RequestPart List<MultipartFile> files) throws Exception {
        return plans.inputs(id,new Inputs(confirmed,digest,json.readTree(values)),files);
    }
    @PostMapping("/plans/{id}/resume") public View resume(@PathVariable @NotBlank @Size(max=64) String id) { return plans.resume(id); }
    @PostMapping("/plans/{id}/cancel") public View cancel(@PathVariable @NotBlank @Size(max=64) String id) { return plans.cancel(id); }
}
