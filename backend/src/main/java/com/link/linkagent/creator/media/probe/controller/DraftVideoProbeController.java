package com.link.linkagent.creator.media.probe.controller;

import com.link.linkagent.creator.media.probe.service.DraftVideoProbeService;
import com.link.linkagent.creator.media.upload.model.DraftVideoResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成片媒体探测接口。
 * <p>
 * P0-1 只做 ffprobe 元信息读取，不启动 ASR、视频理解或正式试映任务。
 */
@Validated
@RestController
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
@RequestMapping("/api/creator/tasks/{taskId}/draft-videos")
public class DraftVideoProbeController {

    private static final String SAFE_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";
    private static final String DEFAULT_OWNER_ID = "default";

    private final DraftVideoProbeService draftVideoProbeService;

    public DraftVideoProbeController(DraftVideoProbeService draftVideoProbeService) {
        this.draftVideoProbeService = draftVideoProbeService;
    }

    /**
     * 查询任务当前成片状态，供创作台决定是否允许进入反馈和 BV 绑定等下游操作。
     */
    @GetMapping("/current")
    public DraftVideoResponse getCurrentDraftVideo(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId) {
        return draftVideoProbeService.getCurrentDraftVideo(DEFAULT_OWNER_ID, taskId);
    }

    /**
     * 读取已上传成片的持久化状态，供页面刷新后恢复媒体探测结果。
     */
    @GetMapping("/{versionId}")
    public DraftVideoResponse getDraftVideo(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @PathVariable
            @NotBlank(message = "成片版本ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "成片版本ID格式不正确")
            String versionId) {
        return draftVideoProbeService.getDraftVideo(DEFAULT_OWNER_ID, taskId, versionId);
    }

    @PostMapping("/{versionId}:probe")
    public DraftVideoResponse probeDraftVideo(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @PathVariable
            @NotBlank(message = "成片版本ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "成片版本ID格式不正确")
            String versionId) {
        return draftVideoProbeService.probeDraftVideo(DEFAULT_OWNER_ID, taskId, versionId);
    }
}
