package com.link.linkagent.creator.suggestion.controller;

import com.link.linkagent.creator.suggestion.model.CreatorSuggestionResponse;
import com.link.linkagent.creator.suggestion.service.PrePublishSuggestionService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发布前优化结果查询接口。
 * 生成和确认统一由工作流接口负责，这里只保留页面刷新所需的只读查询。
 */
@Validated
@RestController
@RequestMapping("/api/creator/tasks/{taskId}/pre-publish")
public class PrePublishSuggestionController {

    private final PrePublishSuggestionService prePublishSuggestionService;

    public PrePublishSuggestionController(PrePublishSuggestionService prePublishSuggestionService) {
        this.prePublishSuggestionService = prePublishSuggestionService;
    }

    @GetMapping("/suggestions")
    public CreatorSuggestionResponse getSuggestion(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,
            @RequestParam(required = false)
            @Size(max = 64, message = "工作流会话ID长度不能超过64个字符")
            String sessionId) {
        if (sessionId == null) {
            return prePublishSuggestionService.getSuggestion(taskId);
        }
        return prePublishSuggestionService.getSuggestion(taskId, sessionId);
    }
}
