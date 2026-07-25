package com.link.linkagent.creator.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
    /** 未发布成片保留天数；供后续清理能力使用，当前不会自动删除原片对象 */
    private int unpublishedRetentionDays = 30;
    /** 上传子配置（分片大小、签名 TTL） */
    private final Upload upload = new Upload();
    /** 媒体处理子配置（ffprobe 路径、超时、Provider 短签 TTL） */
    private final Processing processing = new Processing();
    /** 发布前试映子配置（持久化 Worker 与 ASR Provider） */
    private final Preflight preflight = new Preflight();

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

    public Processing getProcessing() { return processing; }

    public Preflight getPreflight() { return preflight; }

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
        if (processing.ffprobePath == null || processing.ffprobePath.isBlank()) {
            throw new IllegalStateException("ffprobe 命令路径不能为空");
        }
        if (processing.ffmpegPath == null || processing.ffmpegPath.isBlank()) {
            throw new IllegalStateException("ffmpeg 命令路径不能为空");
        }
        if (processing.probeTimeout == null || processing.probeTimeout.isZero() || processing.probeTimeout.isNegative()) {
            throw new IllegalStateException("媒体探测超时时间必须大于0");
        }
        if (processing.providerReadTtl == null || processing.providerReadTtl.isZero() || processing.providerReadTtl.isNegative()) {
            throw new IllegalStateException("Provider 媒体读取短签有效期必须大于0");
        }
        if (processing.providerReadTtl.compareTo(processing.probeTimeout.plusSeconds(5)) <= 0) {
            throw new IllegalStateException("Provider 媒体读取短签有效期必须大于媒体探测超时时间");
        }
        if (processing.ffmpegTimeout == null || processing.ffmpegTimeout.isZero() || processing.ffmpegTimeout.isNegative()) {
            throw new IllegalStateException("FFmpeg 处理超时时间必须大于0");
        }
        if (processing.workRoot == null || processing.workRoot.isBlank()) {
            throw new IllegalStateException("媒体处理工作目录不能为空");
        }
        if (processing.pollIntervalMs <= 0 || processing.leaseDuration == null
                || processing.leaseDuration.isZero() || processing.leaseDuration.isNegative()) {
            throw new IllegalStateException("媒体轮询间隔和租约时长必须大于0");
        }
        if (processing.maxAttempts <= 0) {
            throw new IllegalStateException("媒体处理最大尝试次数必须大于0");
        }
        if (preflight.pollIntervalMs <= 0 || preflight.leaseDuration == null
                || preflight.leaseDuration.isZero() || preflight.leaseDuration.isNegative()) {
            throw new IllegalStateException("试映任务轮询间隔和租约时长必须大于0");
        }
        if (preflight.maxAttempts <= 0 || preflight.providerPollInterval == null
                || preflight.providerPollInterval.isZero() || preflight.providerPollInterval.isNegative()) {
            throw new IllegalStateException("试映任务最大尝试次数和 Provider 轮询间隔必须大于0");
        }
        if (preflight.segmentReviewMaxCount <= 0 || preflight.segmentReviewMaxCount > 5
                || preflight.segmentReviewFps <= 0 || preflight.segmentReviewFps > 10) {
            throw new IllegalStateException("重点片段复核数量必须在1到5之间，抽帧频率必须在0到10之间");
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

    /**
     * 媒体处理配置。
     * <p>
     * Spring Boot 会自动将 creator.media.processing.* 绑定到此内部类的字段。
     */
    public static class Processing {

        /** ffprobe 可执行文件路径；默认从 PATH 查找 */
        private String ffprobePath = "ffprobe";
        /** 单次 ffprobe 探测超时时间 */
        private Duration probeTimeout = Duration.ofSeconds(30);
        /** Provider 读取媒体对象的短签 GET URL 有效期 */
        private Duration providerReadTtl = Duration.ofMinutes(5);
        /** FFmpeg 可执行文件路径；使用参数列表调用，避免 shell 注入和路径转义问题 */
        private String ffmpegPath = "ffmpeg";
        /** 单次 FFmpeg 处理超时时间，防止异常媒体长期占用 Worker */
        private Duration ffmpegTimeout = Duration.ofHours(2);
        /** 媒体处理临时工作目录，视频和派生文件不进入 JVM 内存 */
        private String workRoot = "/var/lib/linkagent-media-work";
        /** Worker 轮询待处理任务的间隔，使用数据库状态恢复任务 */
        private long pollIntervalMs = 2000L;
        /** FFmpeg 长任务的数据库租约时长，避免多实例重复处理 */
        private Duration leaseDuration = Duration.ofMinutes(2);
        /** 单个处理任务允许的最大尝试次数 */
        private int maxAttempts = 3;
        /** 成本估算价格配置版本，便于解释历史估算结果 */
        private String pricingVersion = "2026-07-12-config-v1";
        /** Qwen3-VL-Flash 输入价格，单位为美元/百万 Token */
        private BigDecimal flashInputUsdPerMillionTokens = new BigDecimal("0.022");
        /** Qwen3-VL-Flash 输出价格，单位为美元/百万 Token */
        private BigDecimal flashOutputUsdPerMillionTokens = new BigDecimal("0.215");
        /** Qwen3-VL-Plus 输入价格，单位为美元/百万 Token；仅用于配置估算 */
        private BigDecimal plusInputUsdPerMillionTokens = new BigDecimal("0.42");
        /** Qwen3-VL-Plus 输出价格，单位为美元/百万 Token；仅用于配置估算 */
        private BigDecimal plusOutputUsdPerMillionTokens = new BigDecimal("1.25");
        /** ASR 估算价格，单位为美元/秒；仅用于配置估算 */
        private BigDecimal asrUsdPerSecond = new BigDecimal("0.000035");

        public String getFfprobePath() { return ffprobePath; }
        public void setFfprobePath(String ffprobePath) { this.ffprobePath = ffprobePath; }

        public Duration getProbeTimeout() { return probeTimeout; }
        public void setProbeTimeout(Duration probeTimeout) { this.probeTimeout = probeTimeout; }

        public Duration getProviderReadTtl() { return providerReadTtl; }
        public void setProviderReadTtl(Duration providerReadTtl) { this.providerReadTtl = providerReadTtl; }

        public String getFfmpegPath() { return ffmpegPath; }
        public void setFfmpegPath(String ffmpegPath) { this.ffmpegPath = ffmpegPath; }

        public Duration getFfmpegTimeout() { return ffmpegTimeout; }
        public void setFfmpegTimeout(Duration ffmpegTimeout) { this.ffmpegTimeout = ffmpegTimeout; }

        public String getWorkRoot() { return workRoot; }
        public void setWorkRoot(String workRoot) { this.workRoot = workRoot; }

        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

        public Duration getLeaseDuration() { return leaseDuration; }
        public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

        public String getPricingVersion() { return pricingVersion; }
        public void setPricingVersion(String pricingVersion) { this.pricingVersion = pricingVersion; }

        public BigDecimal getFlashInputUsdPerMillionTokens() { return flashInputUsdPerMillionTokens; }
        public void setFlashInputUsdPerMillionTokens(BigDecimal value) { this.flashInputUsdPerMillionTokens = value; }

        public BigDecimal getFlashOutputUsdPerMillionTokens() { return flashOutputUsdPerMillionTokens; }
        public void setFlashOutputUsdPerMillionTokens(BigDecimal value) { this.flashOutputUsdPerMillionTokens = value; }

        public BigDecimal getPlusInputUsdPerMillionTokens() { return plusInputUsdPerMillionTokens; }
        public void setPlusInputUsdPerMillionTokens(BigDecimal value) { this.plusInputUsdPerMillionTokens = value; }

        public BigDecimal getPlusOutputUsdPerMillionTokens() { return plusOutputUsdPerMillionTokens; }
        public void setPlusOutputUsdPerMillionTokens(BigDecimal value) { this.plusOutputUsdPerMillionTokens = value; }

        public BigDecimal getAsrUsdPerSecond() { return asrUsdPerSecond; }
        public void setAsrUsdPerSecond(BigDecimal asrUsdPerSecond) { this.asrUsdPerSecond = asrUsdPerSecond; }
    }

    /**
     * 发布前试映配置。
     * ASR Key 不在媒体总开关启动时强制校验，避免只使用上传和预处理的部署被无关配置阻断。
     */
    public static class Preflight {

        /** 持久化任务轮询间隔 */
        private long pollIntervalMs = 2000L;
        /** 单次领取租约；Provider 轮询会主动释放租约，不长期占用 Worker */
        private Duration leaseDuration = Duration.ofSeconds(90);
        /** 瞬时失败自动重试上限 */
        private int maxAttempts = 3;
        /** 异步 ASR 状态查询间隔 */
        private Duration providerPollInterval = Duration.ofSeconds(5);
        /** DashScope 中国区基础地址 */
        private String dashScopeBaseUrl = "https://dashscope.aliyuncs.com";
        /** DashScope API Key；通过环境变量注入 */
        private String dashScopeApiKey = "";
        /** 文件转写模型 */
        private String asrModel = "qwen3-asr-flash-filetrans";
        /** 单次全片粗审使用的视频理解模型 */
        private String videoModel = "qwen3-vl-flash";
        /** 全片粗审抽帧频率，P0 按技术方案使用低成本 0.2 fps */
        private double videoFps = 0.2d;
        /** 重点片段使用的视频理解模型；只处理少量短片，不重复读取整片 */
        private String segmentReviewModel = "qwen3-vl-plus";
        /** 单个短片复核抽帧频率 */
        private double segmentReviewFps = 1d;
        /** 单次试映最多复核的重点片段数 */
        private int segmentReviewMaxCount = 5;
        /** HTTP 连接超时 */
        private Duration connectTimeout = Duration.ofSeconds(10);
        /** HTTP 响应超时 */
        private Duration readTimeout = Duration.ofSeconds(30);
        /** 视频理解同步请求最长等待时间 */
        private Duration videoReadTimeout = Duration.ofMinutes(10);

        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

        public Duration getLeaseDuration() { return leaseDuration; }
        public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

        public Duration getProviderPollInterval() { return providerPollInterval; }
        public void setProviderPollInterval(Duration providerPollInterval) { this.providerPollInterval = providerPollInterval; }

        public String getDashScopeBaseUrl() { return dashScopeBaseUrl; }
        public void setDashScopeBaseUrl(String dashScopeBaseUrl) { this.dashScopeBaseUrl = dashScopeBaseUrl; }

        public String getDashScopeApiKey() { return dashScopeApiKey; }
        public void setDashScopeApiKey(String dashScopeApiKey) { this.dashScopeApiKey = dashScopeApiKey; }

        public String getAsrModel() { return asrModel; }
        public void setAsrModel(String asrModel) { this.asrModel = asrModel; }

        public String getVideoModel() { return videoModel; }
        public void setVideoModel(String videoModel) { this.videoModel = videoModel; }

        public double getVideoFps() { return videoFps; }
        public void setVideoFps(double videoFps) { this.videoFps = videoFps; }

        public String getSegmentReviewModel() { return segmentReviewModel; }
        public void setSegmentReviewModel(String segmentReviewModel) { this.segmentReviewModel = segmentReviewModel; }

        public double getSegmentReviewFps() { return segmentReviewFps; }
        public void setSegmentReviewFps(double segmentReviewFps) { this.segmentReviewFps = segmentReviewFps; }

        public int getSegmentReviewMaxCount() { return segmentReviewMaxCount; }
        public void setSegmentReviewMaxCount(int segmentReviewMaxCount) { this.segmentReviewMaxCount = segmentReviewMaxCount; }

        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }

        public Duration getVideoReadTimeout() { return videoReadTimeout; }
        public void setVideoReadTimeout(Duration videoReadTimeout) { this.videoReadTimeout = videoReadTimeout; }
    }
}
