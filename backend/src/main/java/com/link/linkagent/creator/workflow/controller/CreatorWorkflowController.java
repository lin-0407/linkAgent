package com.link.linkagent.creator.workflow.controller;

import com.link.linkagent.creator.suggestion.model.CreatorSuggestionResponse;
import com.link.linkagent.creator.suggestion.model.PrePublishAnalyzeRequest;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowConfirmRequest;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowMessageCreateRequest;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowMessageResponse;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowSessionResponse;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStartRequest;
import com.link.linkagent.creator.workflow.model.CreatorWorkflowStepResponse;
import com.link.linkagent.creator.workflow.model.PrePublishDraftRequest;
import com.link.linkagent.creator.workflow.model.PrePublishDraftResponse;
import com.link.linkagent.creator.workflow.service.CreatorWorkflowService;
import com.link.linkagent.llm.usage.WorkflowUsageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 创作者工作流接口。
 * 当前只开放发布前优化工作流，评论弹幕导入和追问后续再接，避免一次性扩大业务范围。
 */
@Validated
@RestController
@RequestMapping("/api/creator/tasks/{taskId}/workflow")
public class CreatorWorkflowController {

    private final CreatorWorkflowService creatorWorkflowService;

    public CreatorWorkflowController(CreatorWorkflowService creatorWorkflowService) {
        this.creatorWorkflowService = creatorWorkflowService;
    }

    @PostMapping("/pre-publish/start")
    public CreatorWorkflowSessionResponse startPrePublishWorkflow(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @Valid @RequestBody(required = false) CreatorWorkflowStartRequest request) {
        return creatorWorkflowService.startPrePublishWorkflow(taskId, request);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<CreatorWorkflowMessageResponse> listMessages(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @PathVariable
            @NotBlank(message = "工作流会话ID不能为空")
            @Size(max = 64, message = "工作流会话ID长度不能超过64个字符")
            String sessionId) {
        return creatorWorkflowService.listMessages(taskId, sessionId);
    }

    @GetMapping("/sessions/{sessionId}/steps")
    public List<CreatorWorkflowStepResponse> listSteps(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @PathVariable
            @NotBlank(message = "工作流会话ID不能为空")
            @Size(max = 64, message = "工作流会话ID长度不能超过64个字符")
            String sessionId) {
        return creatorWorkflowService.listSteps(taskId, sessionId);
    }

    @GetMapping("/sessions/{sessionId}/usage")
    public WorkflowUsageResponse getWorkflowUsage(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @PathVariable
            @NotBlank(message = "工作流会话ID不能为空")
            @Size(max = 64, message = "工作流会话ID长度不能超过64个字符")
            String sessionId) {
        return creatorWorkflowService.getWorkflowUsage(taskId.trim(), sessionId.trim());
    }

    @GetMapping(value = "/sessions/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeEvents(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @PathVariable
            @NotBlank(message = "工作流会话ID不能为空")
            @Size(max = 64, message = "工作流会话ID长度不能超过64个字符")
            String sessionId) {
        return creatorWorkflowService.subscribeEvents(taskId, sessionId);
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public CreatorWorkflowMessageResponse sendMessage(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @PathVariable
            @NotBlank(message = "工作流会话ID不能为空")
            @Size(max = 64, message = "工作流会话ID长度不能超过64个字符")
            String sessionId,

            @Valid @RequestBody CreatorWorkflowMessageCreateRequest request) {
        return creatorWorkflowService.sendMessage(taskId, sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/pre-publish/align")
    public CreatorWorkflowMessageResponse alignPrePublishIntent(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @PathVariable
            @NotBlank(message = "工作流会话ID不能为空")
            @Size(max = 64, message = "工作流会话ID长度不能超过64个字符")
            String sessionId) {
        return creatorWorkflowService.alignPrePublishIntent(taskId, sessionId);
    }

    @PostMapping("/sessions/{sessionId}/pre-publish/analyze")
    public CreatorSuggestionResponse analyzePrePublishWorkflow(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @PathVariable
            @NotBlank(message = "工作流会话ID不能为空")
            @Size(max = 64, message = "工作流会话ID长度不能超过64个字符")
            String sessionId,

            @Valid @RequestBody(required = false) PrePublishAnalyzeRequest request) {
        return creatorWorkflowService.analyzePrePublishWorkflow(taskId, sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/pre-publish/manuscript-draft")
    public PrePublishDraftResponse generatePrePublishManuscriptDraft(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @PathVariable
            @NotBlank(message = "工作流会话ID不能为空")
            @Size(max = 64, message = "工作流会话ID长度不能超过64个字符")
            String sessionId,

            @Valid @RequestBody(required = false) PrePublishDraftRequest request) {
        return creatorWorkflowService.generatePrePublishManuscriptDraft(taskId, sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/pre-publish/confirm")
    public CreatorWorkflowSessionResponse confirmPrePublishSuggestion(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @PathVariable
            @NotBlank(message = "工作流会话ID不能为空")
            @Size(max = 64, message = "工作流会话ID长度不能超过64个字符")
            String sessionId,

            @Valid @RequestBody CreatorWorkflowConfirmRequest request) {
        return creatorWorkflowService.confirmPrePublishSuggestion(taskId, sessionId, request);
    }
}
