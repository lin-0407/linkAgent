package com.link.linkagent.creator.preference.controller;

import com.link.linkagent.creator.preference.model.CreatorPreferenceResponse;
import com.link.linkagent.creator.preference.service.CreatorPreferenceService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 创作者长期偏好查询接口。
 * 第一轮只开放只读查询，避免在没有人工编辑规则前让外部输入直接污染长期偏好。
 */
@Validated
@RestController
@RequestMapping("/api/creator/preferences")
public class CreatorPreferenceController {

    private final CreatorPreferenceService creatorPreferenceService;

    public CreatorPreferenceController(CreatorPreferenceService creatorPreferenceService) {
        this.creatorPreferenceService = creatorPreferenceService;
    }

    @GetMapping
    public List<CreatorPreferenceResponse> listPreferences(
            @RequestParam(required = false)
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId,

            @RequestParam(required = false)
            @Min(value = 1, message = "查询数量不能小于1")
            @Max(value = 20, message = "查询数量不能超过20")
            Integer limit) {
        return creatorPreferenceService.listPreferences(userId, limit);
    }
}
