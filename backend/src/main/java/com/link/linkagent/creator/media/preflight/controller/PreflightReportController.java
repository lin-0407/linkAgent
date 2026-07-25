package com.link.linkagent.creator.media.preflight.controller;

import com.link.linkagent.creator.media.preflight.model.PreflightReviewResponse;
import com.link.linkagent.creator.media.preflight.model.UpdateEditTaskRequest;
import com.link.linkagent.creator.media.preflight.model.UpdatePreflightIssueRequest;
import com.link.linkagent.creator.media.preflight.service.PreflightReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 发布前体检完成后的问题处置与修改清单接口。 */
@Validated
@RestController
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
@RequestMapping("/api/creator/tasks/{taskId}/preflight")
public class PreflightReportController {

    private static final String SAFE_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";
    private static final String DEFAULT_OWNER_ID = "default";

    private final PreflightReviewService service;

    public PreflightReportController(PreflightReviewService service) {
        this.service = service;
    }

    @PatchMapping("/issues/{issueId}")
    public PreflightReviewResponse updateIssue(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @PathVariable
            @NotBlank(message = "问题ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "问题ID格式不正确")
            String issueId,
            @Valid @RequestBody UpdatePreflightIssueRequest request) {
        return service.updateIssue(DEFAULT_OWNER_ID, taskId, issueId, request);
    }

    @GetMapping("/edit-tasks")
    public List<PreflightReviewResponse.EditTask> listEditTasks(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId) {
        return service.listEditTasks(DEFAULT_OWNER_ID, taskId);
    }

    @PatchMapping("/edit-tasks/{editTaskId}")
    public PreflightReviewResponse.EditTask updateEditTask(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @PathVariable
            @NotBlank(message = "修改任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "修改任务ID格式不正确")
            String editTaskId,
            @Valid @RequestBody UpdateEditTaskRequest request) {
        return service.updateEditTask(DEFAULT_OWNER_ID, taskId, editTaskId, request);
    }
}
