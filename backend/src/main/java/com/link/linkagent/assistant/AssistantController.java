package com.link.linkagent.assistant;

import com.link.linkagent.assistant.AssistantModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 确认操作与聊天分开，模型工具中不存在执行或确认接口。 */
@Validated
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private final PlatformAssistantContextService context;
    private final AssistantActionService actions;
    private final AssistantMapper mapper;
    public AssistantController(PlatformAssistantContextService context,AssistantActionService actions,AssistantMapper mapper) {
        this.context=context; this.actions=actions; this.mapper=mapper;
    }
    @GetMapping("/context")
    public TaskContext context(@RequestParam @NotBlank @Size(max=80) String page,
                               @RequestParam(required=false) @Size(max=64) String taskId) {
        return context.inspect(page,taskId);
    }
    @PostMapping("/actions")
    public ActionProposal prepare(@Valid @RequestBody PrepareAction request) { return actions.prepare(request); }

    @GetMapping("/actions")
    public List<ActionProposal> list(@RequestParam @NotBlank @Size(max=64) String taskId) { return actions.list(taskId); }

    @PostMapping("/actions/{proposalId}/confirm")
    public ActionProposal confirm(@PathVariable @NotBlank @Size(max=64) String proposalId,
                                  @Valid @RequestBody ConfirmAction request) { return actions.confirm(proposalId,request); }

    @GetMapping("/executions")
    public List<AgentTrace> executions(@RequestParam @NotBlank @Size(max=64) String taskId) {
        context.inspect("/creator",taskId);
        return mapper.listTraces(taskId);
    }
}
