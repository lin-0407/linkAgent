package com.link.linkagent.creator.media.storage;

import java.time.Instant;

/**
 * 浏览器上传单个分片所需的短时签名结果。
 * <p>
 * url 字段包含认证签名，是 Bearer 凭证级别的敏感信息：
 * <ul>
 *   <li>不得写入数据库或应用日志</li>
 *   <li>不得在 HTTP 响应中设置缓存头</li>
 *   <li>有效期由 expiresAt 控制（默认 15 分钟）</li>
 * </ul>
 *
 * @param partNumber 分片序号，范围 1–10000
 * @param url        短时 PUT 预签名 URL；不得写入数据库或日志
 * @param expiresAt  URL 过期时间；前端据此在过期前刷新签名
 */
public record PresignedUploadPart(
        int partNumber,
        String url,
        Instant expiresAt
) {
}
