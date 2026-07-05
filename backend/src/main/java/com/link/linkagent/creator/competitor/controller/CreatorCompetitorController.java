package com.link.linkagent.creator.competitor.controller;

import com.link.linkagent.creator.competitor.model.CompetitorAnalyzeByReferenceRequest;
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

    /**
     * 基于参考案例触发竞品分析（P1-1：参考案例融入竞品分析体系）。
     * <p>
     * 与 {@code /analyze} 的区别：本端点不需要用户手动填写竞品文稿，
     * 而是从参考案例知识库（creator_reference_video + creator_reference_video_item）
     * 读取已通过 BV 导入管道采集的竞品数据，自动组装为分析上下文。
     * <p>
     * 调用流程：用户在知识库页面点击竞品卡片上的「对比我的创作」按钮 →
     * 选择要对比的任务 → 前端调用本端点 → 后端读取参考案例数据 →
     * 组装虚拟竞品样本 → 复用已有的 LLM 分析 + 报告持久化逻辑。
     *
     * @param taskId  关联的创作任务标识
     * @param request 基于参考案例的分析请求，包含 referenceVideoId 和可选的分析参数
     * @return 完整的竞品分析报告响应
     */
    @PostMapping("/analyze-by-reference")
    public CreatorCompetitorReportResponse analyzeByReferenceVideo(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @Valid @RequestBody CompetitorAnalyzeByReferenceRequest request) {
        return creatorCompetitorService.analyzeByReferenceVideo(
                taskId, request.referenceVideoId(), request.toAnalyzeRequest());
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
