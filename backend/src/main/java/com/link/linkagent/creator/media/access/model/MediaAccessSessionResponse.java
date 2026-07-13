package com.link.linkagent.creator.media.access.model;

import java.time.Instant;

/**
 * 媒体访问会话 API 响应。
 * <p>
 * 前端根据此响应判断：
 * <ul>
 *   <li>enabled=false → 当前部署未启用媒体能力，隐藏媒体相关 UI</li>
 *   <li>enabled=true, authenticated=false → 显示访问口令输入框</li>
 *   <li>enabled=true, authenticated=true → 显示媒体上传界面，expiresAt 用于倒计时</li>
 * </ul>
 *
 * @param enabled       当前部署是否启用阶段 7 私有媒体能力
 * @param authenticated 是否已经通过共享访问口令认证（有有效会话）
 * @param expiresAt     会话过期时间；未认证时为 null
 */
public record MediaAccessSessionResponse(
        boolean enabled,
        boolean authenticated,
        Instant expiresAt
) {
}
