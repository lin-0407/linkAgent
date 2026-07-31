package com.link.linkagent.creator.media.storage;

import com.link.linkagent.creator.media.config.ObjectStorageProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * AWS SDK Java 2.x 的 S3 兼容实现，P0 默认连接阿里云 OSS 香港地域。
 * <p>
 * 关键 OSS 兼容注意事项：
 * <ul>
 *   <li>OSS 只支持虚拟主机寻址（pathStyleAccess=false）</li>
 *   <li>OSS 不支持 aws-chunked 传输编码（chunkedEncodingEnabled=false）</li>
 *   <li>OSS 的 ETag 算法与 S3 不同：不是 MD5，而是 OSS 自定义算法</li>
 *   <li>因此 ETag 必须原样回传，禁止自行重新计算或修改大小写</li>
 * </ul>
 * <p>
 * 所有方法只抛出 MediaStorageException，业务层不直接依赖 AWS SDK 异常类型，
 * 确保将来切换为官方 OSS SDK 时不影响上传业务逻辑。
 */
@Service
// 只有媒体总开关开启时才创建，避免无 OSS 凭证的部署启动失败
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class S3ObjectStorageService implements ObjectStorageService {

    // S3Client：后端控制面操作（CreateMultipartUpload、Complete、Abort、Head、Delete）
    // 浏览器读取派生媒体使用 browserEndpoint，不能把 Provider 地址暴露给页面。
    private final S3Presigner browserReadPresigner;
    private final S3Client s3Client;
    // S3Presigner：生成浏览器直传 OSS 的短时签名 URL（上传分片不经过本服务）
    private final S3Presigner uploadPartPresigner;
    // S3Presigner：生成 Provider/ffprobe 读取私有对象的短时签名 URL
    private final S3Presigner providerReadPresigner;
    // 对象存储配置（Bucket、Region 等）
    private final ObjectStorageProperties properties;

    public S3ObjectStorageService(S3Client s3Client,
                                   @Qualifier("mediaS3Presigner") S3Presigner uploadPartPresigner,
                                   @Qualifier("mediaProviderS3Presigner") S3Presigner providerReadPresigner,
                                   ObjectStorageProperties properties) {
        this.s3Client = s3Client;
        this.uploadPartPresigner = uploadPartPresigner;
        this.browserReadPresigner = uploadPartPresigner;
        this.providerReadPresigner = providerReadPresigner;
        this.properties = properties;
    }

    /**
     * 在 OSS 上创建分片上传，返回 Upload ID。
     * <p>
     * Content-Type 在创建时声明，后续所有分片继承该类型，
     * 完成后的对象自动获得正确的 Content-Type 响应头。
     *
     * @param objectKey   对象键（后端生成，格式：users/{ownerId}/tasks/{taskId}/.../source.mp4）
     * @param contentType 媒体类型（P0 仅 video/mp4）
     * @return 包含 Upload ID 的句柄，后续签名、完成、取消都必须携带
     */
    @Override
    public MultipartUploadHandle createMultipartUpload(String objectKey, String contentType) {
        try {
            // 构建 S3 CreateMultipartUpload 请求
            CreateMultipartUploadResponse response = s3Client.createMultipartUpload(
                    CreateMultipartUploadRequest.builder()
                            .bucket(properties.getBucket())   // 桶名从配置读取
                            .key(objectKey)                   // 后端生成的对象键
                            .contentType(contentType)          // 声明媒体类型
                            .build()
            );
            // 只返回 Upload ID，其余 SDK 响应字段不暴露给业务层
            return new MultipartUploadHandle(response.uploadId());
        } catch (SdkException exception) {
            // 所有 AWS SDK 异常统一转为 MediaStorageException，屏蔽厂商细节
            throw storageFailure("创建媒体分片上传失败", exception);
        }
    }

    /**
     * 为指定分片生成短时预签名 PUT URL。
     * <p>
     * 预签名 URL 包含认证签名，浏览器拿到后可直接 PUT 数据到 OSS，
     * 不经过本服务中转。URL 有效期由 signatureDuration 控制（默认 15 分钟）。
     *
     * @param bucketName       对象所在 Bucket
     * @param objectKey         对象键
     * @param uploadId          Multipart Upload ID
     * @param partNumber        分片序号（1-based）
     * @param signatureDuration 签名有效期
     * @return 预签名结果（含上传 URL 和过期时间）
     */
    @Override
    public PresignedUploadPart presignUploadPart(String objectKey,
                                                 String uploadId,
                                                 int partNumber,
                                                 Duration signatureDuration) {
        try {
            // 构建底层 UploadPart 请求（不含签名，只是描述"要上传哪个分片"）
            UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .uploadId(uploadId)    // 关联到已创建的分片上传
                    .partNumber(partNumber) // 分片序号
                    .build();
            // 包装为预签名请求：指定签名有效期
            UploadPartPresignRequest presignRequest = UploadPartPresignRequest.builder()
                    .signatureDuration(signatureDuration) // 默认 15 分钟
                    .uploadPartRequest(uploadPartRequest)
                    .build();
            // 调用 Presigner 生成预签名 URL
            PresignedUploadPartRequest signedRequest = uploadPartPresigner.presignUploadPart(presignRequest);
            return new PresignedUploadPart(
                    partNumber,
                    signedRequest.url().toString(), // 含签名的完整 URL
                    Instant.now().plus(signatureDuration) // 过期时间
            );
        } catch (SdkException exception) {
            throw storageFailure("生成媒体分片上传签名失败", exception);
        }
    }

    /**
     * 为私有媒体对象生成短时 GET URL。
     * <p>
     * 该 URL 后续会交给 ffprobe 或云端 Provider 读取，不能持久化，也不能写日志。
     *
     * @param objectKey         对象键
     * @param signatureDuration 签名有效期
     * @return 短时读取签名结果
     */
    @Override
    public PresignedObjectRead presignGetObject(String bucketName,
                                                String objectKey,
                                                Duration signatureDuration) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)

                    .build();
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(signatureDuration)
                    .getObjectRequest(getObjectRequest)
                    .build();
            PresignedGetObjectRequest signedRequest = providerReadPresigner.presignGetObject(presignRequest);
            return new PresignedObjectRead(
                    signedRequest.url().toString(),
                    Instant.now().plus(signatureDuration)
            );
        } catch (SdkException exception) {
            throw storageFailure("生成媒体读取签名失败", exception);
        }
    }

    @Override
    public PresignedObjectRead presignBrowserGetObject(String bucketName,
                                                       String objectKey,
                                                       Duration signatureDuration) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(signatureDuration)
                    .getObjectRequest(getObjectRequest)
                    .build();
            PresignedGetObjectRequest signedRequest = browserReadPresigner.presignGetObject(presignRequest);
            return new PresignedObjectRead(signedRequest.url().toString(), Instant.now().plus(signatureDuration));
        } catch (SdkException exception) {
            throw storageFailure("生成媒体预览签名失败", exception);
        }
    }

    /**
     * 完成分片上传：将所有已上传分片合并为完整对象。
     * <p>
     * 必须传入所有分片的 partNumber 和 ETag，且顺序无所谓（OSS 按 partNumber 自行排序）。
     * ETag 必须与浏览器 PUT 时 OSS 返回的完全一致，原样回传，禁止修改大小写。
     *
     * @param objectKey 对象键
     * @param uploadId  Multipart Upload ID
     * @param parts     所有已完成分片的 ETag 列表
     */
    @Override
    public void completeMultipartUpload(String objectKey,
                                        String uploadId,
                                        List<CompletedUploadPart> parts) {
        try {
            // 将业务层的 CompletedUploadPart 转换为 AWS SDK 的 CompletedPart
            List<CompletedPart> completedParts = parts.stream()
                    .map(part -> CompletedPart.builder()
                            .partNumber(part.partNumber())
                            // OSS 的 ETag 算法与 S3 不同，必须原样回传，禁止重新计算或修改大小写
                            .eTag(part.etag())
                            .build())
                    .toList();
            // 调用 CompleteMultipartUpload 合并分片
            s3Client.completeMultipartUpload(
                    CompleteMultipartUploadRequest.builder()
                            .bucket(properties.getBucket())
                            .key(objectKey)
                            .uploadId(uploadId)
                            // 包装已完成分片列表
                            .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                            .build()
            );
        } catch (SdkException exception) {
            throw storageFailure("完成媒体分片上传失败", exception);
        }
    }

    /**
     * 取消分片上传：释放 OSS 上所有已上传但未合并的分片。
     * <p>
     * 重要：已上传但未完成的分片会持续产生存储费用，必须及时取消。
     * 本方法对 NoSuchUploadException 幂等处理：上传已被 OSS 自动清理时，
     * 业务状态仍可安全收敛为 ABORTED。
     *
     * @param bucketName 对象所在 Bucket
     * @param objectKey  对象键
     * @param uploadId   要取消的 Multipart Upload ID
     */
    @Override
    public void abortMultipartUpload(String bucketName, String objectKey, String uploadId) {
        try {
            s3Client.abortMultipartUpload(
                    AbortMultipartUploadRequest.builder()
                            .bucket(bucketName)
                            .key(objectKey)
                            .uploadId(uploadId)
                            .build()
            );
        } catch (NoSuchUploadException ignored) {
            // 此异常表示 OSS 上已不存在该上传（可能已被生命周期规则清理）
            // 这不影响业务：取消的目标是"OSS 上不残留未完成分片"，目标已达成
        } catch (SdkException exception) {
            throw storageFailure("取消媒体分片上传失败", exception);
        }
    }

    /**
     * 获取对象元数据（大小、媒体类型、ETag）。
     * <p>
     * 用于上传完成后校验实际对象是否与客户端声明一致。
     * 这是轻量级的 HEAD 请求，不下载对象内容。
     *
     * @param objectKey 对象键
     * @return 对象元数据
     */
    @Override
    public StoredObjectMetadata headObject(String objectKey) {
        try {
            HeadObjectResponse response = s3Client.headObject(
                    HeadObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(objectKey)
                            .build()
            );
            // 只提取业务需要的三个字段：大小、类型、ETag
            return new StoredObjectMetadata(response.contentLength(), response.contentType(), response.eTag());
        } catch (SdkException exception) {
            throw storageFailure("读取媒体对象元数据失败", exception);
        }
    }

    /**
     * 将私有对象流式下载到任务工作目录，避免大文件进入 JVM 内存。
     */
    @Override
    public void downloadObject(String bucketName, String objectKey, Path targetFile) {
        try {
            s3Client.getObject(
                    GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(objectKey)
                            .build(),
                    ResponseTransformer.toFile(targetFile)
            );
        } catch (SdkException exception) {
            throw storageFailure("下载媒体对象失败", exception);
        }
    }

    @Override
    public void putObject(String bucketName, String objectKey, Path sourceFile, String contentType) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(objectKey)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromFile(sourceFile)
            );
        } catch (SdkException exception) {
            throw storageFailure("上传派生媒体失败", exception);
        }
    }

    @Override
    public PresignedObjectRead presignAsrGetObject(String bucketName,
                                                   String objectKey,
                                                   Duration signatureDuration) {
        try {
            return OssV1ObjectReadSigner.sign(
                    properties.getProviderEndpoint(),
                    bucketName,
                    objectKey,
                    properties.getAccessKey(),
                    properties.getSecretKey(),
                    signatureDuration
            );
        } catch (RuntimeException exception) {
            throw new MediaStorageException("生成 ASR 媒体读取签名失败", exception);
        }
    }

    @Override
    public void deleteObject(String bucketName, String objectKey) {
        try {
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(bucketName)
                            .key(objectKey)
                            .build()
            );
        } catch (SdkException exception) {
            throw storageFailure("删除媒体对象失败", exception);
        }
    }

    /**
     * 将 AWS SDK 异常包装为 MediaStorageException。
     * <p>
     * 错误消息只包含固定中文摘要，不拼接 Endpoint、对象键或签名参数，
     * 避免敏感信息通过异常消息泄露到日志或 API 响应中。
     *
     * @param message   中文错误摘要
     * @param exception 原始 SDK 异常（不对外暴露）
     * @return 包装后的业务异常
     */
    private MediaStorageException storageFailure(String message, SdkException exception) {
        // 原始异常通过 cause 保留，供日志框架记录堆栈（日志级别可控）
        // 但对外消息仅使用中文摘要，不暴露内部细节
        return new MediaStorageException(message, exception);
    }
}
