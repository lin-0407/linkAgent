package com.link.linkagent.creator.media.access.model;

import java.time.Instant;

/**
 * 服务端解析出的可信媒体访问身份。
 * <p>
 * 由 Filter 从 HttpOnly Cookie 中解析并注入 request 属性，
 * Controller 通过该属性获取 ownerId，零信任客户端传入的任何归属参数。
 *
 * @param ownerId   归属标识；P0 固定为 "default"，未来账号系统接入后替换为真实用户 ID
 * @param expiresAt Redis 会话过期时间；前端可据此在过期前提示用户刷新
 */
public record MediaAccessIdentity(
        String ownerId,
        Instant expiresAt
) {
}
