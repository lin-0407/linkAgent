package com.link.linkagent.creator.media.storage;

import java.time.Instant;

/**
 * Provider 或后端探测读取私有媒体对象所需的短时签名结果。
 * <p>
 * url 字段包含读取权限签名，等同短期 Bearer 凭证，不能写入数据库或日志。
 *
 * @param url       短时 GET 预签名 URL
 * @param expiresAt URL 过期时间
 */
public record PresignedObjectRead(
        String url,
        Instant expiresAt
) {
}
