package com.link.linkagent.creator.media.storage;

/**
 * 完成 Multipart Upload 时提交给对象存储的分片事实。
 * <p>
 * etag 来自浏览器 PUT 响应头，由对象存储生成，本服务不得自行计算或修改大小写。
 * OSS 的 ETag 与 S3（MD5）算法不同，因此不能像标准 S3 那样用 MD5 验证。
 *
 * @param partNumber 分片序号，范围 1–10000
 * @param etag       浏览器从分片 PUT 响应头读取并登记的不透明 ETag 字符串
 */
public record CompletedUploadPart(int partNumber, String etag) {
}
