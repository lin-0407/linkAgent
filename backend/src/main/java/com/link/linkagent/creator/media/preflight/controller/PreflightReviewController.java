package com.link.linkagent.creator.media.preflight.controller;

import com.link.linkagent.creator.media.preflight.model.CreatePreflightReviewRequest;
import com.link.linkagent.creator.media.preflight.model.PreflightReviewResponse;
import com.link.linkagent.creator.media.preflight.service.PreflightReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** P0-3/P0-4a 发布前试映与单视角体检任务接口。 */
@Validated
@RestController
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
@RequestMapping("/api/creator/tasks/{taskId}/preflight-jobs")
public class PreflightReviewController {

    private static final String SAFE_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";
    private static final String DEFAULT_OWNER_ID = "default";

    private final PreflightReviewService service;

    public PreflightReviewController(PreflightReviewService service) {
        this.service = service;
    }

    @PostMapping
    public PreflightReviewResponse create(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @RequestHeader("Idempotency-Key")
            @NotBlank(message = "Idempotency-Key不能为空")
            @Size(max = 128, message = "Idempotency-Key长度不能超过128个字符")
            String idempotencyKey,
            @Valid @RequestBody CreatePreflightReviewRequest request) {
        return service.create(DEFAULT_OWNER_ID, taskId, idempotencyKey, request);
    }

    @GetMapping("/current")
    public PreflightReviewResponse getCurrent(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @RequestParam
            @NotBlank(message = "成片版本ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "成片版本ID格式不正确")
            String versionId) {
        return service.getCurrent(DEFAULT_OWNER_ID, taskId, versionId);
    }

    @GetMapping("/{reviewId}")
    public PreflightReviewResponse get(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @PathVariable
            @NotBlank(message = "试映任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "试映任务ID格式不正确")
            String reviewId) {
        return service.get(DEFAULT_OWNER_ID, taskId, reviewId);
    }

    @GetMapping(value = "/{reviewId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @PathVariable
            @NotBlank(message = "试映任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "试映任务ID格式不正确")
            String reviewId,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "事件游标不能小于0")
            @Max(value = Long.MAX_VALUE, message = "事件游标无效")
            long afterSequence) {
        return service.subscribe(DEFAULT_OWNER_ID, taskId, reviewId, afterSequence);
    }

    @PostMapping("/{reviewId}:cancel")
    public PreflightReviewResponse cancel(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @PathVariable
            @NotBlank(message = "试映任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "试映任务ID格式不正确")
            String reviewId) {
        return service.cancel(DEFAULT_OWNER_ID, taskId, reviewId);
    }

    @PostMapping("/{reviewId}:retry")
    public PreflightReviewResponse retry(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @PathVariable
            @NotBlank(message = "试映任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "试映任务ID格式不正确")
            String reviewId) {
        return service.retry(DEFAULT_OWNER_ID, taskId, reviewId);
    }
}
