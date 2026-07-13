package com.link.linkagent.creator.media.storage;

import java.time.Duration;
import java.util.List;

/**
 * 私有媒体对象存储边界接口。
 * <p>
 * 业务服务不直接依赖 AWS SDK，是为了在 OSS S3 兼容 Spike 失败时可以切换官方 OSS SDK，
 * 而不改动上传会话、数据库状态和 Controller 契约。只需提供一个新的实现类即可。
 * <p>
 * 接口方法覆盖完整的 Multipart Upload 生命周期：
 * <ol>
 *   <li>createMultipartUpload — 创建分片上传，获取 Upload ID</li>
 *   <li>presignUploadPart — 为指定分片生成短时预签名 PUT URL</li>
 *   <li>completeMultipartUpload — 提交所有分片 ETag 合并为完整对象</li>
 *   <li>abortMultipartUpload — 取消上传，释放未合并分片</li>
 *   <li>headObject — 获取对象元数据（大小、类型、ETag）</li>
 *   <li>deleteObject — 删除对象</li>
 * </ol>
 * <p>
 * 所有方法只抛出 MediaStorageException，调用方无需区分底层实现。
 */
public interface ObjectStorageService {

    /**
     * 创建分片上传，返回对象存储生成的 Upload ID。
     *
     * @param objectKey   对象键（后端生成，格式：users/{ownerId}/tasks/{taskId}/.../source.mp4）
     * @param contentType 媒体类型声明（P0 仅 video/mp4）
     * @return Upload ID 句柄
     */
    MultipartUploadHandle createMultipartUpload(String objectKey, String contentType);

    /**
     * 为指定分片生成短时预签名 PUT URL。
     * <p>
     * 生成的 URL 含认证签名，浏览器可直接 PUT 分片数据而不经过本服务。
     *
     * @param objectKey         对象键
     * @param uploadId          Multipart Upload ID
     * @param partNumber        分片序号（1-based，范围 1–10000）
     * @param signatureDuration 签名有效期
     * @return 预签名结果（含上传 URL 和过期时间）
     */
    PresignedUploadPart presignUploadPart(String objectKey,
                                          String uploadId,
                                          int partNumber,
                                          Duration signatureDuration);

    /**
     * 提交所有分片 ETag，完成 Multipart Upload。
     * <p>
     * ETag 必须原样回传（来自浏览器 PUT 响应头），不得自行计算或修改大小写。
     *
     * @param objectKey 对象键
     * @param uploadId  Multipart Upload ID
     * @param parts     已完成分片列表（含 partNumber 和 etag）
     */
    void completeMultipartUpload(String objectKey,
                                 String uploadId,
                                 List<CompletedUploadPart> parts);

    /**
     * 取消分片上传，释放对象存储上所有已上传但未合并的分片。
     * <p>
     * 必须幂等实现：如果上传已被自动清理，不应抛出异常。
     *
     * @param objectKey 对象键
     * @param uploadId  要取消的 Multipart Upload ID
     */
    void abortMultipartUpload(String objectKey, String uploadId);

    /**
     * 获取对象元数据（HEAD 请求，轻量，不下载内容）。
     *
     * @param objectKey 对象键
     * @return 对象元数据（大小、类型、ETag）
     */
    StoredObjectMetadata headObject(String objectKey);

    /**
     * 删除对象。
     *
     * @param objectKey 对象键
     */
    void deleteObject(String objectKey);
}
