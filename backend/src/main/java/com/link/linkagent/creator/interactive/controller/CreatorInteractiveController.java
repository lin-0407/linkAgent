package com.link.linkagent.creator.interactive.controller;

import com.link.linkagent.creator.interactive.model.CreativeOptionsRegenerateRequest;
import com.link.linkagent.creator.interactive.model.InteractiveTaskCreateRequest;
import com.link.linkagent.creator.interactive.model.InteractiveTaskResponse;
import com.link.linkagent.creator.interactive.service.CreatorInteractiveService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 交互式创作接口。
 * 先提供第一阶段闭环，后续发布前优化、BV 绑定和视频分析继续挂在同一任务上下文下扩展。
 */
@Validated
@RestController
@RequestMapping("/api/creator/interactive/tasks")
public class CreatorInteractiveController {

    private final CreatorInteractiveService creatorInteractiveService;

    public CreatorInteractiveController(CreatorInteractiveService creatorInteractiveService) {
        this.creatorInteractiveService = creatorInteractiveService;
    }

    @PostMapping
    public InteractiveTaskResponse createInteractiveTask(
            @Valid @RequestBody InteractiveTaskCreateRequest request) {
        return creatorInteractiveService.createInteractiveTask(request);
    }

    @GetMapping("/{taskId}")
    public InteractiveTaskResponse getInteractiveTask(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId) {
        return creatorInteractiveService.getInteractiveTask(taskId);
    }

    @PostMapping("/{taskId}/creative-options/regenerate")
    public InteractiveTaskResponse regenerateOptions(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @Valid @RequestBody(required = false) CreativeOptionsRegenerateRequest request) {
        return creatorInteractiveService.regenerateOptions(taskId, request);
    }

    @PostMapping("/{taskId}/creative-options/{optionId}/confirm")
    public InteractiveTaskResponse confirmOption(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @PathVariable
            @NotBlank(message = "创意卡片ID不能为空")
            @Size(max = 64, message = "创意卡片ID长度不能超过64个字符")
            String optionId) {
        return creatorInteractiveService.confirmOption(taskId, optionId);
    }
}
