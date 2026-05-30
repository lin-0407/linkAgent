package com.link.linkagent.creator.feedback.controller;

import com.link.linkagent.creator.feedback.model.CreatorFeedbackAnalyzeRequest;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackChatRequest;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackChatResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackDashboardResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackFetchRequest;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackFetchResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackImportResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackSaveRequest;
import com.link.linkagent.creator.feedback.service.CreatorFeedbackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 评论弹幕反馈接口。
 * 围绕 taskId 挂载样例和分析报告，保证复盘阶段能复用同一条创作任务链路。
 */
@Validated
@RestController
@RequestMapping("/api/creator/tasks/{taskId}/feedback")
public class CreatorFeedbackController {

    private final CreatorFeedbackService creatorFeedbackService;

    public CreatorFeedbackController(CreatorFeedbackService creatorFeedbackService) {
        this.creatorFeedbackService = creatorFeedbackService;
    }

    @PostMapping
    public CreatorFeedbackResponse saveFeedback(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @Valid @RequestBody CreatorFeedbackSaveRequest request) {
        return creatorFeedbackService.saveFeedback(taskId, request);
    }

    @GetMapping
    public CreatorFeedbackResponse getFeedback(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId) {
        return creatorFeedbackService.getFeedback(taskId);
    }

    @PostMapping("/analyze")
    public CreatorFeedbackReportResponse analyze(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @Valid @RequestBody CreatorFeedbackAnalyzeRequest request) {
        return creatorFeedbackService.analyze(taskId, request);
    }

    @GetMapping("/report")
    public CreatorFeedbackReportResponse getReport(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId) {
        return creatorFeedbackService.getReport(taskId);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CreatorFeedbackImportResponse importFeedback(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @RequestParam("file")
            @NotNull(message = "导入文件不能为空")
            MultipartFile file) {
        return creatorFeedbackService.importFeedback(taskId, file);
    }

    @PostMapping("/fetch")
    public CreatorFeedbackFetchResponse fetchFeedback(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @Valid @RequestBody CreatorFeedbackFetchRequest request) {
        return creatorFeedbackService.fetchFeedback(taskId, request);
    }

    @GetMapping("/dashboard")
    public CreatorFeedbackDashboardResponse getDashboard(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId) {
        return creatorFeedbackService.getDashboard(taskId);
    }

    @PostMapping("/chat")
    public CreatorFeedbackChatResponse chat(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @Valid @RequestBody CreatorFeedbackChatRequest request) {
        return creatorFeedbackService.chat(taskId, request);
    }
}
