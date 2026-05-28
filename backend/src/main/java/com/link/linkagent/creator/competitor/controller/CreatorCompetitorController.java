package com.link.linkagent.creator.competitor.controller;

import com.link.linkagent.creator.competitor.model.CreatorCompetitorAnalyzeRequest;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorReportResponse;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSaveRequest;
import com.link.linkagent.creator.competitor.model.CreatorCompetitorSampleResponse;
import com.link.linkagent.creator.competitor.service.CreatorCompetitorService;
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
 * 同类型视频竞品分析接口。
 * 竞品数据只接收用户主动提供的 BV 号、视频名称和分析文本，避免把核心业务绑定到平台抓取能力。
 */
@Validated
@RestController
@RequestMapping("/api/creator/tasks/{taskId}/competitors")
public class CreatorCompetitorController {

    private final CreatorCompetitorService creatorCompetitorService;

    public CreatorCompetitorController(CreatorCompetitorService creatorCompetitorService) {
        this.creatorCompetitorService = creatorCompetitorService;
    }

    @PostMapping
    public CreatorCompetitorSampleResponse saveCompetitorVideo(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @Valid @RequestBody CreatorCompetitorSaveRequest request) {
        return creatorCompetitorService.saveCompetitorVideo(taskId, request);
    }

    @GetMapping
    public CreatorCompetitorSampleResponse getCompetitorVideo(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId) {
        return creatorCompetitorService.getCompetitorVideo(taskId);
    }

    @PostMapping("/analyze")
    public CreatorCompetitorReportResponse analyze(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @Valid @RequestBody CreatorCompetitorAnalyzeRequest request) {
        return creatorCompetitorService.analyze(taskId, request);
    }

    @GetMapping("/report")
    public CreatorCompetitorReportResponse getReport(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId) {
        return creatorCompetitorService.getReport(taskId);
    }
}
