package com.link.linkagent.creator.task.controller;

import com.link.linkagent.creator.task.model.PrePublishSettingsResponse;
import com.link.linkagent.creator.task.model.PrePublishSettingsUpdateRequest;
import com.link.linkagent.creator.task.service.CreatorPrePublishSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/creator/tasks/{taskId}/pre-publish-settings")
public class CreatorPrePublishSettingsController {

    private final CreatorPrePublishSettingsService service;

    public CreatorPrePublishSettingsController(CreatorPrePublishSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public PrePublishSettingsResponse getSettings(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId) {
        return service.getSettings(taskId);
    }

    @PutMapping
    public PrePublishSettingsResponse saveSettings(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,
            @Valid @RequestBody PrePublishSettingsUpdateRequest request) {
        return service.saveSettings(taskId, request);
    }
}
