package com.link.linkagent.creator.bilibili.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * BV 绑定请求体。
 * Jakarta Validation 保证入参不为空且格式正确：
 * - BV 号必须符合 B 站格式 BV + 10 位字母数字
 * - UID 为纯数字字符串，第一版只校验非空和长度
 * - userId 用于确认操作归属
 */
public record BindBvRequest(
        @NotBlank(message = "用户ID不能为空")
        @Size(max = 64, message = "用户ID长度不能超过64个字符")
        String userId,

        @NotBlank(message = "B站UID不能为空")
        @Size(max = 32, message = "B站UID长度不能超过32个字符")
        @Pattern(regexp = "^[0-9]+$", message = "B站UID只能包含数字")
        String bilibiliUid,

        @NotBlank(message = "BV号不能为空")
        @Pattern(regexp = "^BV[0-9A-Za-z]{10}$", message = "BV号格式不正确，应为BV+10位字母数字")
        String bvid
) {
}
