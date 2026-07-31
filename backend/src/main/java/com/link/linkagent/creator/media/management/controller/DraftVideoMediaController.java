package com.link.linkagent.creator.media.management.controller;

import com.link.linkagent.creator.media.management.service.MediaDeletionService;
import com.link.linkagent.creator.media.probe.service.DraftVideoProbeService;
import com.link.linkagent.creator.media.upload.model.DraftVideoResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 成片媒体管理接口；删除文件不会删除创作任务或试映报告。 */
@Validated
@RestController
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
@RequestMapping("/api/creator/tasks/{taskId}/draft-videos")
public class DraftVideoMediaController {

    private static final String SAFE_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";
    private static final String DEFAULT_OWNER_ID = "default";

    private final MediaDeletionService deletionService;
    private final DraftVideoProbeService probeService;

    public DraftVideoMediaController(MediaDeletionService deletionService,
                                     DraftVideoProbeService probeService) {
        this.deletionService = deletionService;
        this.probeService = probeService;
    }

    /** 删除 OSS 媒体后返回保留的成片事实，页面据此切换为历史只读状态。 */
    @DeleteMapping("/{versionId}/media")
    public DraftVideoResponse deleteMedia(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "任务ID格式不正确")
            String taskId,
            @PathVariable
            @NotBlank(message = "成片版本ID不能为空")
            @Pattern(regexp = SAFE_ID_PATTERN, message = "成片版本ID格式不正确")
            String versionId) {
        deletionService.deleteMedia(DEFAULT_OWNER_ID, taskId, versionId);
        return probeService.getDraftVideo(DEFAULT_OWNER_ID, taskId, versionId);
    }
}
