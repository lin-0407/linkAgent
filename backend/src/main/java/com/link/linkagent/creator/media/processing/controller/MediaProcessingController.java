package com.link.linkagent.creator.media.processing.controller;

import com.link.linkagent.creator.media.processing.model.MediaProcessingAssetReadUrlResponse;
import com.link.linkagent.creator.media.processing.model.MediaProcessingEstimate;
import com.link.linkagent.creator.media.processing.model.MediaProcessingJobResponse;
import com.link.linkagent.creator.media.processing.model.MediaProcessingOptionsRequest;
import com.link.linkagent.creator.media.processing.service.MediaProcessingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P0-2 媒体预处理接口。
 * 页面使用轮询读取持久化任务快照，不依赖进程内 SSE 状态。
 */
@Validated
@RestController
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
@RequestMapping("/api/creator/tasks/{taskId}/draft-videos/{versionId}")
public class MediaProcessingController {

    private static final String SAFE_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";
    private static final String DEFAULT_OWNER_ID = "default";

    private final MediaProcessingService processingService;

    public MediaProcessingController(MediaProcessingService processingService) {
        this.processingService = processingService;
    }

    @PostMapping("/processing-estimate")
    public MediaProcessingEstimate estimate(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @PathVariable
            @NotBlank(message = "成片版本ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "成片版本ID格式不正确")
            String versionId,
            @Valid @RequestBody MediaProcessingOptionsRequest request) {
        return processingService.estimate(DEFAULT_OWNER_ID, taskId, versionId, request);
    }

    @PostMapping("/processing-jobs")
    public MediaProcessingJobResponse createJob(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @PathVariable
            @NotBlank(message = "成片版本ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "成片版本ID格式不正确")
            String versionId,
            @Valid @RequestBody MediaProcessingOptionsRequest request) {
        return processingService.createJob(DEFAULT_OWNER_ID, taskId, versionId, request);
    }

    @GetMapping("/processing-jobs/current")
    public MediaProcessingJobResponse getCurrentJob(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @PathVariable
            @NotBlank(message = "成片版本ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "成片版本ID格式不正确")
            String versionId) {
        return processingService.getCurrentJob(DEFAULT_OWNER_ID, taskId, versionId);
    }

    @PostMapping("/processing-jobs/{jobId}:retry")
    public MediaProcessingJobResponse retryJob(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @PathVariable
            @NotBlank(message = "成片版本ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "成片版本ID格式不正确")
            String versionId,
            @PathVariable
            @NotBlank(message = "处理任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "处理任务ID格式不正确")
            String jobId) {
        return processingService.retryJob(DEFAULT_OWNER_ID, taskId, versionId, jobId);
    }

    @PostMapping("/processing-assets/{assetId}:read-url")
    public MediaProcessingAssetReadUrlResponse createAssetReadUrl(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @PathVariable
            @NotBlank(message = "成片版本ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "成片版本ID格式不正确")
            String versionId,
            @PathVariable
            @NotBlank(message = "派生素材ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "派生素材ID格式不正确")
            String assetId) {
        return processingService.createAssetReadUrl(DEFAULT_OWNER_ID, taskId, versionId, assetId);
    }
}
