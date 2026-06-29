package com.link.linkagent.creator.bilibili.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * B站账号绑定请求体。
 * 第一版只需要用户提供 B 站 UID，后续如果需要 OAuth 再扩展字段。
 */
public record BindAccountRequest(
        @NotBlank(message = "用户ID不能为空")
        @Size(max = 64, message = "用户ID长度不能超过64个字符")
        String userId,

        @NotBlank(message = "B站UID不能为空")
        @Size(max = 32, message = "B站UID长度不能超过32个字符")
        String bilibiliUid
) {
}
