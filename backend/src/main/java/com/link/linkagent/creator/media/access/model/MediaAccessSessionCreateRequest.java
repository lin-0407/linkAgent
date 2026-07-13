package com.link.linkagent.creator.media.access.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建单部署媒体访问会话的请求。
 * <p>
 * 仅包含共享访问口令一个字段，不包含用户名、密码等传统认证信息。
 * 这是因为 P0 阶段尚未接入账号系统，使用部署者配置的统一口令。
 *
 * @param accessCode 部署者配置的共享访问口令；长度不超过 256 字符
 */
public record MediaAccessSessionCreateRequest(
        @NotBlank(message = "媒体访问口令不能为空")
        @Size(max = 256, message = "媒体访问口令长度不能超过256个字符")
        String accessCode
) {
}
