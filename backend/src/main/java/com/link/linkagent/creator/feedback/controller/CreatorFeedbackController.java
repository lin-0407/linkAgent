package com.link.linkagent.creator.feedback.controller;

import com.link.linkagent.creator.feedback.model.CreatorFeedbackAnalyzeRequest;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackReportResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackResponse;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackSaveRequest;
import com.link.linkagent.creator.feedback.service.CreatorFeedbackService;
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
}
