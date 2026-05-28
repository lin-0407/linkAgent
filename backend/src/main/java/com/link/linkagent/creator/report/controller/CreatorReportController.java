package com.link.linkagent.creator.report.controller;

import com.link.linkagent.creator.report.model.CreatorReportAnalyzeRequest;
import com.link.linkagent.creator.report.model.CreatorReportResponse;
import com.link.linkagent.creator.report.service.CreatorReportService;
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
 * 创作复盘报告接口。
 * 复盘报告挂在 taskId 下，是为了把发布前建议、观众反馈和后续偏好沉淀串成同一条业务链路。
 */
@Validated
@RestController
@RequestMapping("/api/creator/tasks/{taskId}/report")
public class CreatorReportController {

    private final CreatorReportService creatorReportService;

    public CreatorReportController(CreatorReportService creatorReportService) {
        this.creatorReportService = creatorReportService;
    }

    @PostMapping("/analyze")
    public CreatorReportResponse analyze(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @Valid @RequestBody CreatorReportAnalyzeRequest request) {
        return creatorReportService.analyze(taskId, request);
    }

    @GetMapping
    public CreatorReportResponse getReport(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId) {
        return creatorReportService.getReport(taskId);
    }
}
