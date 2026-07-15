package com.link.linkagent.creator.bilibili.controller;

import com.link.linkagent.creator.bilibili.model.BilibiliAccountResponse;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoResponse;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoSyncResponse;
import com.link.linkagent.creator.bilibili.model.BindAccountRequest;
import com.link.linkagent.creator.bilibili.model.BindBvRequest;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingResponse;
import com.link.linkagent.creator.bilibili.service.CreatorBilibiliService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * B站账号绑定与任务视频绑定接口（P0-3）。
 * 独立于已有的 CreatorInteractiveController，路径前缀 /api/creator/bilibili。
 * 提供账号绑定、公开视频同步、BV 绑定校验和已绑定视频列表四个能力。
 * <p>
 * 所有入参使用 Jakarta Validation 校验。
 * 路径变量也加 @NotBlank + @Size，防止空值穿透到 Service 层。
 */
@Validated
@RestController
@RequestMapping("/api/creator/bilibili")
public class CreatorBilibiliController {

    private final CreatorBilibiliService bilibiliService;

    public CreatorBilibiliController(CreatorBilibiliService bilibiliService) {
        this.bilibiliService = bilibiliService;
    }

    /**
     * 绑定或更新 B 站账号。
     * 用户只需要提供 B 站 UID，第一版不做 OAuth 授权。
     */
    @PostMapping("/accounts")
    public BilibiliAccountResponse bindAccount(@Valid @RequestBody BindAccountRequest request) {
        return bilibiliService.bindAccount(request);
    }

    /**
     * 查询 B 站账号绑定状态。
     * 未绑定时返回 404，前端据此展示绑定入口。
     */
    @GetMapping("/accounts/{userId}")
    public BilibiliAccountResponse getAccount(
            @PathVariable
            @NotBlank(message = "用户ID不能为空")
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "用户ID格式不正确")
            String userId) {
        BilibiliAccountResponse account = bilibiliService.getAccount(userId);
        if (account == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "未找到B站账号绑定，请先绑定UID");
        }
        return account;
    }

    /** 触发 B 站公开视频同步，并校验当前用户任务 BV 的视频归属。 */
    @PostMapping("/accounts/{userId}/sync")
    public BilibiliVideoSyncResponse syncVideos(
            @PathVariable
            @NotBlank(message = "用户ID不能为空")
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "用户ID格式不正确")
            String userId) {
        return bilibiliService.syncVideos(userId);
    }

    /**
     * 获取某 B 站 UID 下已绑定任务的视频列表。
     * 这是视频分析页的核心数据源：只展示和平台任务关联的视频，不展示账号下全部视频。
     */
    @GetMapping("/accounts/{bilibiliUid}/linked-videos")
    public List<BilibiliVideoResponse> getLinkedVideos(
            @PathVariable
            @NotBlank(message = "B站UID不能为空")
            @Size(max = 32, message = "B站UID长度不能超过32个字符")
            @Pattern(regexp = "^[0-9]+$", message = "B站UID只能包含数字")
            String bilibiliUid,
            @RequestParam(defaultValue = "default")
            @NotBlank(message = "用户ID不能为空")
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "用户ID格式不正确")
            String userId) {
        return bilibiliService.getLinkedVideos(bilibiliUid, userId);
    }

    /**
     * 将 BV 号绑定到创作任务。
     * 已有可信缓存时直接完成校验，否则进入"等待校验"状态，后续同步后才能展示视频卡片。
     */
    @PostMapping("/tasks/{taskId}/video-binding")
    public TaskVideoBindingResponse bindBvToTask(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "任务ID格式不正确")
            String taskId,
            @Valid @RequestBody BindBvRequest request) {
        return bilibiliService.bindBvToTask(taskId, request);
    }

    /**
     * 查询任务视频绑定。
     * 不存在时返回 404，前端据此决定展示绑定输入还是已绑定信息。
     */
    @GetMapping("/tasks/{taskId}/video-binding")
    public TaskVideoBindingResponse getTaskBinding(
            @PathVariable
            @NotBlank(message = "任务ID不能为空")
            @Size(max = 64, message = "任务ID长度不能超过64个字符")
            @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "任务ID格式不正确")
            String taskId) {
        TaskVideoBindingResponse binding = bilibiliService.getTaskBinding(taskId);
        if (binding == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "该任务尚未绑定BV号");
        }
        return binding;
    }
}
