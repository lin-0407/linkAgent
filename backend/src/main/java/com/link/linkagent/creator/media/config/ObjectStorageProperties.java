package com.link.linkagent.creator.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * S3 兼容对象存储连接配置。
 * <p>
 * P0 默认连接阿里云 OSS 香港地域。内部、浏览器和 Provider 端点分开保存，
 * 是为了未来允许后端走内网、浏览器和云端模型走公网，而不把某个部署拓扑写死在业务代码中。
 * <p>
 * 三个端点的默认值相同（均为 OSS 香港公网地址），实际部署时可通过环境变量分别覆盖。
 * 例如后端在阿里云 ECS 上时可设置 internalEndpoint 为内网地址以节省流量费用。
 */
@Component
@ConfigurationProperties(prefix = "storage.s3") // 绑定 application.yml 中 storage.s3 下的所有配置
public class ObjectStorageProperties {

    /** 后端控制面访问 OSS 的端点（CreateMultipartUpload、Complete 等）；部署在同 Region 时可改为内网地址 */
    private String internalEndpoint = "https://s3.oss-cn-hongkong.aliyuncs.com";
    /** 浏览器直传 OSS 的端点（预签名 URL 中的 host）；必须是公网可达地址 */
    private String browserEndpoint = "https://s3.oss-cn-hongkong.aliyuncs.com";
    /** 云端 AI Provider 访问 OSS 的端点（后续视频分析等场景）；默认为公网地址 */
    private String providerEndpoint = "https://s3.oss-cn-hongkong.aliyuncs.com";
    /** S3 签名 Region；阿里云官方 SDK 示例使用 aws-global，改为其它值可能导致签名校验失败 */
    private String region = "aws-global";
    /** RAM 用户的 AccessKey ID；生产环境必须通过环境变量注入，不能硬编码 */
    private String accessKey = "";
    /** RAM 用户的 AccessKey Secret；与 accessKey 配对使用 */
    private String secretKey = "";
    /** OSS Bucket 名称；必须与 AccessKey 对应的 RAM 用户有读写权限 */
    private String bucket = "";
    /** 是否使用 Path-Style 寻址；阿里云 OSS 必须为 false（虚拟主机寻址） */
    private boolean pathStyleAccess = false;
    /** 是否启用 AWS Chunked Encoding；阿里云 OSS 必须为 false */
    private boolean chunkedEncodingEnabled = false;

    // ===== getter/setter =====

    public String getInternalEndpoint() { return internalEndpoint; }
    public void setInternalEndpoint(String internalEndpoint) { this.internalEndpoint = internalEndpoint; }

    public String getBrowserEndpoint() { return browserEndpoint; }
    public void setBrowserEndpoint(String browserEndpoint) { this.browserEndpoint = browserEndpoint; }

    public String getProviderEndpoint() { return providerEndpoint; }
    public void setProviderEndpoint(String providerEndpoint) { this.providerEndpoint = providerEndpoint; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public boolean isPathStyleAccess() { return pathStyleAccess; }
    public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }

    public boolean isChunkedEncodingEnabled() { return chunkedEncodingEnabled; }
    public void setChunkedEncodingEnabled(boolean chunkedEncodingEnabled) { this.chunkedEncodingEnabled = chunkedEncodingEnabled; }

    /**
     * 媒体总开关打开后才调用，避免未使用阶段 7 的部署因为空 OSS 凭证无法启动。
     * <p>
     * 校验规则：
     * <ul>
     *   <li>所有必填文本字段不为空白</li>
     *   <li>阿里云 OSS 场景下 pathStyleAccess 必须为 false（OSS 只支持虚拟主机寻址）</li>
     *   <li>阿里云 OSS 场景下 chunkedEncodingEnabled 必须为 false（OSS 不支持 aws-chunked）</li>
     * </ul>
     * OSS 场景的判断依据是 endpoint 包含 "aliyuncs.com"。
     */
    public void validateConfigured() {
        // 逐一校验必填字段，错误信息只描述缺失项，不暴露密钥内容
        requireText(internalEndpoint, "对象存储内部 Endpoint 未配置");
        requireText(browserEndpoint, "对象存储浏览器 Endpoint 未配置");
        requireText(providerEndpoint, "对象存储 Provider Endpoint 未配置");
        requireText(region, "对象存储签名 Region 未配置");
        requireText(accessKey, "对象存储 AccessKey 未配置");
        requireText(secretKey, "对象存储 SecretKey 未配置");
        requireText(bucket, "对象存储 Bucket 未配置");
        // 阿里云 OSS S3 兼容接口的硬性约束：必须使用虚拟主机寻址
        if (pathStyleAccess && internalEndpoint.contains("aliyuncs.com")) {
            throw new IllegalStateException("阿里云 OSS 的 S3 兼容接口必须使用虚拟主机寻址");
        }
        // 阿里云 OSS 不支持 aws-chunked 传输编码，强制要求关闭
        if (chunkedEncodingEnabled && internalEndpoint.contains("aliyuncs.com")) {
            throw new IllegalStateException("阿里云 OSS 不支持 aws-chunked 传输编码");
        }
    }

    /**
     * 要求字符串值不为空且不为空白。
     *
     * @param value   待校验的值
     * @param message 校验失败时的中文错误信息
     */
    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
    }
}
