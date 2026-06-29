package com.link.linkagent.creator.interactive.controller;

import com.link.linkagent.creator.interactive.model.CreativeOptionsRegenerateRequest;
import com.link.linkagent.creator.interactive.model.InteractiveTaskCreateRequest;
import com.link.linkagent.creator.interactive.model.InteractiveTaskResponse;
import com.link.linkagent.creator.interactive.service.CreatorInteractiveService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

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

    /**
     * 创建交互式创作任务。
     * 只创建任务和会话记录，不再自动生成方向卡。
     * 后续需依次调用：上传背景文档(可选) → AI理解确认 → 生成方向卡。
     */
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

    /**
     * 上传补充背景文档。
     * 支持多文件上传，每个文件通过 Tika 提取纯文本后追加到会话背景上下文。
     * 文件大小限制 10 MB/个，支持 PDF、DOCX、TXT、MD 等常见文档格式。
     */
    @PostMapping(value = "/{taskId}/context-documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InteractiveTaskResponse uploadContextDocuments(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @RequestPart("files")
            List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "请至少上传一个文件"
            );
        }
        return creatorInteractiveService.appendContextDocuments(taskId, files);
    }

    /**
     * AI 理解确认。
     * 调用 LLM 理解用户想法 + 补充背景资料，返回结构化理解摘要。
     * 理解确认是生成方向卡的前置必要步骤，不可跳过。
     */
    @PostMapping("/{taskId}/understand")
    public InteractiveTaskResponse triggerUnderstanding(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId) {
        return creatorInteractiveService.triggerUnderstanding(taskId);
    }

    /**
     * 首次生成创意方向卡。
     * 必须在 AI 理解确认完成（READY）后才能调用。
     * 传入额外要求可微调方向，传 null 表示无额外要求。
     */
    @PostMapping("/{taskId}/creative-options/generate")
    public InteractiveTaskResponse generateCreativeOptions(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @Valid @RequestBody(required = false) CreativeOptionsRegenerateRequest request) {
        String extraRequirement = request == null ? null : request.extraRequirement();
        return creatorInteractiveService.generateCreativeOptions(taskId, extraRequirement);
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
