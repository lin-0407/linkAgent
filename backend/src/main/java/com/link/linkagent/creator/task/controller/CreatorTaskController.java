package com.link.linkagent.creator.task.controller;

import com.link.linkagent.creator.task.model.CreatorTaskCreateRequest;
import com.link.linkagent.creator.task.model.CreatorTaskResponse;
import com.link.linkagent.creator.task.model.CreatorTaskUpdateRequest;
import com.link.linkagent.creator.task.model.CreatorTaskSummaryResponse;
import com.link.linkagent.creator.task.service.CreatorTaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * UP 主智能工作台的创作任务接口。
 * 先提供最小任务输入能力，后续发布前优化和复盘报告都围绕 taskId 扩展。
 */
@Validated
@RestController
@RequestMapping("/api/creator/tasks")
public class CreatorTaskController {

    private final CreatorTaskService creatorTaskService;

    public CreatorTaskController(CreatorTaskService creatorTaskService) {
        this.creatorTaskService = creatorTaskService;
    }

    @PostMapping
    public CreatorTaskResponse createTask(@Valid @RequestBody CreatorTaskCreateRequest request) {
        return creatorTaskService.createTask(request);
    }

    @PutMapping("/{taskId}")
    public CreatorTaskResponse updateTask(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @Valid @RequestBody CreatorTaskUpdateRequest request) {
        return creatorTaskService.updateTask(taskId, request);
    }

    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTask(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId) {
        creatorTaskService.deleteTask(taskId);
    }

    @PostMapping(value = "/{taskId}/materials/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CreatorTaskResponse importMaterial(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId,

            @RequestParam("materialType")
            @NotBlank(message = "材料类型不能为空")
            @Pattern(
                    regexp = "TITLE_DRAFT|DESCRIPTION_DRAFT|MANUSCRIPT|SUBTITLE",
                    message = "材料类型只能是 TITLE_DRAFT、DESCRIPTION_DRAFT、MANUSCRIPT 或 SUBTITLE"
            )
            String materialType,

            @RequestParam("file")
            @NotNull(message = "导入文件不能为空")
            MultipartFile file) {
        return creatorTaskService.importMaterial(taskId, materialType, file);
    }

    @GetMapping
    public List<CreatorTaskSummaryResponse> listTasks(
            @RequestParam(required = false)
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId,

            @RequestParam(required = false)
            @Min(value = 1, message = "查询数量不能小于1")
            @Max(value = 100, message = "查询数量不能超过100")
            Integer limit) {
        return creatorTaskService.listTasks(userId, limit);
    }

    @GetMapping("/{taskId}")
    public CreatorTaskResponse getTask(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            String taskId) {
        return creatorTaskService.getTask(taskId);
    }
}
