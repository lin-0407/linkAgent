package com.link.linkagent.creator.profile.controller;

import com.link.linkagent.creator.profile.model.CreatorProfileRecord;
import com.link.linkagent.creator.profile.service.CreatorProfileService;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 创作者画像与反馈事件接口。
 * 画像只读接口返回当前用户的风格/语气/受众认知摘要，
 * 反馈事件接口接收前端反馈按钮提交的采纳/拒绝动作。
 */
@Validated
@RestController
@RequestMapping("/api/creator/profile")
public class CreatorProfileController {

    private final CreatorProfileService creatorProfileService;

    public CreatorProfileController(CreatorProfileService creatorProfileService) {
        this.creatorProfileService = creatorProfileService;
    }

    /**
     * 获取当前用户的创作者画像。
     * 不存在时返回空画像而非 404——前端按"暂无画像"展示即可。
     */
    @GetMapping
    public CreatorProfileRecord getProfile(
            @RequestParam(required = false)
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId) {
        CreatorProfileRecord profile = creatorProfileService.getProfile(userId);
        if (profile == null) {
            // 返回空画像对象，让前端统一处理"暂无画像"的状态展示
            CreatorProfileRecord empty = new CreatorProfileRecord();
            empty.setCreatorId(userId);
            empty.setStyleTags("[]");
            return empty;
        }
        return profile;
    }

    /**
     * 记录一条创作者反馈事件。
     * 前端建议卡片的"采纳/拒绝/不太好"按钮点击后调用此接口。
     * 事件写入后自动检查是否需要触发画像增量更新。
     */
    @PostMapping("/events")
    public void recordFeedbackEvent(@RequestBody Map<String, Object> body) {
        String userId = getStringField(body, "userId");
        String eventType = getStringField(body, "eventType");
        String taskId = getStringField(body, "taskId");

        if (userId == null || eventType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId 和 eventType 不能为空");
        }

        creatorProfileService.recordEvent(userId, eventType, taskId, body);
        // 事件写入后尝试触发画像增量更新（内部检查阈值）
        creatorProfileService.tryTriggerProfileUpdate(userId);
    }

    /**
     * 手动触发画像刷新（立即执行，不检查阈值）。
     * 供用户在设置页或调试时手动更新画像。
     */
    @PostMapping("/refresh")
    public CreatorProfileRecord refreshProfile(
            @RequestParam(required = false)
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId) {
        return creatorProfileService.refreshProfile(userId);
    }

    @SuppressWarnings("unchecked")
    private String getStringField(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        return value.toString().trim();
    }
}
