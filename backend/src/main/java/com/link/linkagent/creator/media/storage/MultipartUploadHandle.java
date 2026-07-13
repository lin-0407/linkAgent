package com.link.linkagent.creator.media.storage;

/**
 * 对象存储创建 Multipart Upload 后返回的最小句柄。
 * <p>
 * 使用 Java record 而非普通 class，因为句柄是不可变的纯数据载体：
 * 创建后 uploadId 不应被修改。
 * <p>
 * uploadId 由 OSS/S3 服务端生成，用于后续签名、完成和取消操作中标识本次上传。
 *
 * @param uploadId 存储服务生成的上传 ID；后续所有分片操作都必须携带此 ID
 */
public record MultipartUploadHandle(String uploadId) {
}
