package com.link.linkagent.creator.media.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * S3 客户端 Spring 配置。
 * <p>
 * 只有媒体总开关开启时才创建客户端 Bean，避免现有功能在没有 OSS 凭证时受到影响。
 * 后端控制面请求和浏览器预签名分别使用不同的 endpoint，这样将来切换内外网地址时
 * 不需要修改上传业务代码——只需修改 application.yml 中的配置即可。
 * <p>
 * 创建的 Bean：
 * <ul>
 *   <li>S3Configuration — OSS 兼容行为配置（禁用 path-style、禁用 chunked encoding）</li>
 *   <li>StaticCredentialsProvider — 基于 AccessKey/SecretKey 的凭证提供者</li>
 *   <li>S3Client — 后端控制面 S3 客户端（使用 internalEndpoint）</li>
 *   <li>S3Presigner — 浏览器预签名生成器（使用 browserEndpoint）</li>
 *   <li>S3Presigner — Provider 读取预签名生成器（使用 providerEndpoint）</li>
 * </ul>
 */
@Configuration
// 只有 creator.media.enabled=true 时才加载此配置类，避免空凭证导致 Bean 创建失败
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class ObjectStorageConfiguration {

    /**
     * S3 兼容行为配置 Bean。
     * <p>
     * 必须先校验配置完整性（凭证、端点非空等），再创建 Bean。
     * OSS 兼容要点：关闭 path-style 和 chunked encoding。
     */
    @Bean
    public S3Configuration mediaS3Configuration(ObjectStorageProperties properties) {
        // 先校验配置完整性，不通过则直接阻止启动
        properties.validateConfigured();
        return S3Configuration.builder()
                // OSS 官方要求使用虚拟主机寻址（bucket 作为域名一部分，而非 URL 路径后缀）
                .pathStyleAccessEnabled(properties.isPathStyleAccess())
                // OSS 不支持 aws-chunked 传输编码，Java SDK 2.x 必须显式关闭
                .chunkedEncodingEnabled(properties.isChunkedEncodingEnabled())
                .build();
    }

    /**
     * 静态凭证提供者 Bean。
     * <p>
     * 基于 RAM 用户的 AccessKey/SecretKey，生产环境通过环境变量注入。
     * P0 不使用 STS 临时凭证或 Instance Profile，保持部署简单。
     */
    @Bean
    public StaticCredentialsProvider mediaS3CredentialsProvider(ObjectStorageProperties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                        properties.getAccessKey(),   // RAM 用户 AccessKey ID
                        properties.getSecretKey()    // RAM 用户 AccessKey Secret
                )
        );
    }

    /**
     * 后端控制面 S3 客户端 Bean。
     * <p>
     * 用于 CreateMultipartUpload、CompleteMultipartUpload、AbortMultipartUpload、
     * HeadObject、DeleteObject 等操作。使用 internalEndpoint，部署在同 Region
     * 时可改为内网地址以降低延迟和流量费用。
     */
    @Bean
    public S3Client mediaS3Client(ObjectStorageProperties properties,
                                 S3Configuration mediaS3Configuration,
                                 StaticCredentialsProvider mediaS3CredentialsProvider) {
        return S3Client.builder()
                // 覆盖默认 AWS 端点，指向阿里云 OSS
                .endpointOverride(URI.create(properties.getInternalEndpoint()))
                // 签名 Region，阿里云使用 aws-global
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(mediaS3CredentialsProvider)
                .serviceConfiguration(mediaS3Configuration)
                .build();
    }

    /**
     * 浏览器预签名生成器 Bean。
     * <p>
     * 用于为浏览器直传分片生成短时预签名 PUT URL。使用 browserEndpoint，
     * 生成的 URL 中的 host 必须是浏览器可达的公网地址。
     * S3Presigner 不发起网络请求，只是本地计算签名并构造 URL。
     */
    @Bean
    public S3Presigner mediaS3Presigner(ObjectStorageProperties properties,
                                        S3Configuration mediaS3Configuration,
                                        StaticCredentialsProvider mediaS3CredentialsProvider) {
        return S3Presigner.builder()
                // 预签名 URL 中的 host 使用 browserEndpoint（公网可达）
                .endpointOverride(URI.create(properties.getBrowserEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(mediaS3CredentialsProvider)
                .serviceConfiguration(mediaS3Configuration)
                .build();
    }

    /**
     * Provider 读取预签名生成器 Bean。
     * <p>
     * 用于生成给 ffprobe、Qwen 或 ASR 读取媒体对象的短时 GET URL。它使用 providerEndpoint，
     * 避免把浏览器直传地址和云端模型回源地址强行绑定为同一个部署拓扑。
     */
    @Bean
    public S3Presigner mediaProviderS3Presigner(ObjectStorageProperties properties,
                                                S3Configuration mediaS3Configuration,
                                                StaticCredentialsProvider mediaS3CredentialsProvider) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.getProviderEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(mediaS3CredentialsProvider)
                .serviceConfiguration(mediaS3Configuration)
                .build();
    }
}
