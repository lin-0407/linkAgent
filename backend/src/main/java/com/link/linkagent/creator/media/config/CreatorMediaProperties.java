package com.link.linkagent.creator.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 阶段 7 私有媒体能力配置。
 * <p>
 * 总开关默认关闭，是为了避免作者尚未配置 OSS 时意外开放大文件接口。
 * 只有真正开启媒体能力时才由业务服务执行完整性校验，
 * 这样不会影响尚未启用阶段 7 的现有部署启动。
 * <p>
 * 所有配置项都支持环境变量覆盖，遵循 Spring Boot 的 relaxed binding 规则。
 * 例如 creator.media.upload.presign-ttl 对应环境变量 CREATOR_MEDIA_UPLOAD_PRESIGN_TTL。
 */
@Component
@ConfigurationProperties(prefix = "creator.media") // 绑定 application.yml 中 creator.media 下的所有配置
public class CreatorMediaProperties {

    /** 媒体能力总开关；默认关闭，避免未配置时意外开放 */
    private boolean enabled = false;
    /** 单个媒体文件最大字节数；默认 1.5GB，与 OSS 分片上传 10000 片上限匹配 */
    private long maxFileBytes = 1_500_000_000L;
    /** 单个媒体文件最大时长毫秒；默认 30 分钟 */
    private long maxDurationMs = 1_800_000L;
    /** 未发布成片保留天数；超期自动清理原片对象 */
    private int unpublishedRetentionDays = 30;
    /** 上传子配置（分片大小、签名 TTL） */
    private final Upload upload = new Upload();

    // ===== 顶层 getter/setter =====

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }

    public long getMaxDurationMs() { return maxDurationMs; }
    public void setMaxDurationMs(long maxDurationMs) { this.maxDurationMs = maxDurationMs; }

    public int getUnpublishedRetentionDays() { return unpublishedRetentionDays; }
    public void setUnpublishedRetentionDays(int unpublishedRetentionDays) { this.unpublishedRetentionDays = unpublishedRetentionDays; }

    public Upload getUpload() { return upload; }

    /**
     * 开启媒体能力时执行运行前校验。
     * <p>
     * 错误信息只描述缺失项，不回显任何密钥内容。
     * 在校验中不检查存储配置（由 ObjectStorageProperties.validateConfigured 负责）。
     */
    public void validateEnabledConfiguration() {
        if (!enabled) {
            // 媒体能力未开启：不应调用此方法，但防御性检查
            throw new IllegalStateException("媒体能力尚未启用");
        }
        if (maxFileBytes <= 0) {
            throw new IllegalStateException("媒体文件大小上限必须大于0");
        }
        if (maxDurationMs <= 0) {
            throw new IllegalStateException("媒体时长上限必须大于0");
        }
        // 分片大小下限 5 MiB：这是 S3 Multipart Upload 的最低要求（最后一片除外）
        if (upload.partSizeBytes < 5 * 1024 * 1024) {
            throw new IllegalStateException("媒体分片大小不能小于5MiB");
        }
        // 分片签名批量上限：防止单次请求签名过多导致响应体过大
        if (upload.maxSignBatch <= 0 || upload.maxSignBatch > 20) {
            throw new IllegalStateException("单次分片签名数量必须在1到20之间");
        }
        if (upload.presignTtl == null || upload.presignTtl.isZero() || upload.presignTtl.isNegative()) {
            throw new IllegalStateException("媒体分片签名有效期必须大于0");
        }
        if (upload.abandonedTtl == null || upload.abandonedTtl.isZero() || upload.abandonedTtl.isNegative()) {
            throw new IllegalStateException("媒体上传会话有效期必须大于0");
        }
        if (unpublishedRetentionDays <= 0) {
            throw new IllegalStateException("未发布媒体保留天数必须大于0");
        }
    }

    /**
     * 上传子配置。
     * <p>
     * Spring Boot 会自动将 creator.media.upload.* 绑定到此内部类的字段。
     */
    public static class Upload {

        /** 单分片目标字节数；默认 16 MiB（16777216 字节），符合 S3 推荐的 5 MiB–5 GiB 范围 */
        private int partSizeBytes = 16 * 1024 * 1024;
        /** 单次分片签名批量上限；默认 20 */
        private int maxSignBatch = 20;
        /** 分片预签名 URL 有效期；默认 15 分钟，给网络波动留足时间 */
        private Duration presignTtl = Duration.ofMinutes(15);
        /** 上传会话最长存活时间；默认 24 小时，超时未完成则标记 EXPIRED */
        private Duration abandonedTtl = Duration.ofHours(24);

        public int getPartSizeBytes() { return partSizeBytes; }
        public void setPartSizeBytes(int partSizeBytes) { this.partSizeBytes = partSizeBytes; }

        public int getMaxSignBatch() { return maxSignBatch; }
        public void setMaxSignBatch(int maxSignBatch) { this.maxSignBatch = maxSignBatch; }

        public Duration getPresignTtl() { return presignTtl; }
        public void setPresignTtl(Duration presignTtl) { this.presignTtl = presignTtl; }

        public Duration getAbandonedTtl() { return abandonedTtl; }
        public void setAbandonedTtl(Duration abandonedTtl) { this.abandonedTtl = abandonedTtl; }
    }
}
