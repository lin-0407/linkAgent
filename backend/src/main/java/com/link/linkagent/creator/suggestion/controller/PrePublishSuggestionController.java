package com.link.linkagent.creator.suggestion.controller;

import com.link.linkagent.creator.suggestion.model.CreatorSuggestionResponse;
import com.link.linkagent.creator.suggestion.model.PrePublishAnalyzeRequest;
import com.link.linkagent.creator.suggestion.service.PrePublishSuggestionService;
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
 * 发布前优化接口。
 * 该接口围绕 taskId 工作，保证标题简介建议能回挂到具体创作任务。
 */
@Validated
@RestController
@RequestMapping("/api/creator/tasks/{taskId}/pre-publish")
public class PrePublishSuggestionController {

    private final PrePublishSuggestionService prePublishSuggestionService;

    public PrePublishSuggestionController(PrePublishSuggestionService prePublishSuggestionService) {
        this.prePublishSuggestionService = prePublishSuggestionService;
    }

    @PostMapping("/analyze")
    public CreatorSuggestionResponse analyze(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @Valid @RequestBody PrePublishAnalyzeRequest request) {
        return prePublishSuggestionService.analyze(taskId, request);
    }

    @GetMapping("/suggestions")
    public CreatorSuggestionResponse getSuggestion(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId) {
        return prePublishSuggestionService.getSuggestion(taskId);
    }
}
