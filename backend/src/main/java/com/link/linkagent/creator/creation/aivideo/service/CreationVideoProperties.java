package com.link.linkagent.creator.creation.aivideo.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 视频生成的部署配置。
 * <p>
 * 业务不写死具体厂商：部署者按兼容的异步协议填写服务地址、模型与密钥，密钥只留在后端。
 * 价格只是记账上界，不是供应商账单；能力入口只返回脱敏状态。
 */
@Component
@ConfigurationProperties(prefix = "creator.creation.video")
public class CreationVideoProperties {
    /** 未显式开启时不产生任何付费调用，能力和估算接口仍可说明未配置原因。 */
    private boolean enabled = false;
    /** 兼容协议供应商标识，写入任务事实，不代表已经通过真实供应商验收。 */
    private String provider = "compatible";
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";
    /** 输出画幅；模型实际返回尺寸以探测结果为准，不符合时不会静默拉伸。 */
    private int width = 1280;
    private int height = 720;
    /** 供应商支持的整段生成秒数，选择覆盖场景时长的最小值请求。 */
    private List<Integer> supportedDurationsSeconds = new ArrayList<>(List.of(5, 10));
    /** 按生成秒数计价的配置上界（美元/秒），写入价格快照，不冒充真实账单。 */
    private BigDecimal usdPerSecond = new BigDecimal("0.10");
    /** 单次任务的最长等待；超时保留任务标识并交给作者处理，不重复提交。 */
    private Duration taskTimeout = Duration.ofMinutes(10);
    private Duration pollInterval = Duration.ofSeconds(5);
    /** 提交与查询请求的超时，避免外部调用长期占住执行线程。 */
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(60);
    /** 下载生成结果的连接与读取超时。 */
    private Duration downloadTimeout = Duration.ofMinutes(5);
    private int maxScenes = 24;
    private int maxPromptCharacters = 600;
    /** 规范化视频上限；超过渲染器单素材限制的片段不能进入工程。 */
    private long maxNormalizedBytes = 32L * 1024 * 1024;

    /** 供应商参数是否完整；运行期开关单独由设置面板控制。 */
    public boolean providerConfigured() {
        return provider != null && !provider.isBlank()
                && baseUrl != null && !baseUrl.isBlank()
                && apiKey != null && !apiKey.isBlank()
                && model != null && !model.isBlank()
                && width >= 256 && height >= 256 && width <= 3840 && height <= 2160
                && supportedDurations() && usdPerSecond != null && usdPerSecond.signum() > 0;
    }

    /** 部署默认值；设置面板可以对它做运行期覆盖。 */
    public boolean configured() {
        return enabled && providerConfigured();
    }

    public List<Integer> durations() {
        return supportedDurationsSeconds == null ? List.of() : List.copyOf(supportedDurationsSeconds);
    }

    private boolean supportedDurations() {
        if (supportedDurationsSeconds == null || supportedDurationsSeconds.isEmpty()) return false;
        for (Integer value : supportedDurationsSeconds)
            if (value == null || value < 1 || value > 60) return false;
        return true;
    }

    public void validate() {
        if (!configured()) throw new IllegalStateException("视频生成配置不完整：需要启用、服务地址、密钥、模型与支持的时长");
        if (taskTimeout == null || taskTimeout.isNegative() || taskTimeout.isZero() || taskTimeout.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalStateException("视频任务超时配置无效");
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero())
            throw new IllegalStateException("视频轮询间隔配置无效");
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero())
            throw new IllegalStateException("视频请求超时配置无效");
        if (maxScenes < 1 || maxScenes > 48) throw new IllegalStateException("视频场景数量上限无效");
        if (maxPromptCharacters < 1 || maxPromptCharacters > 600) throw new IllegalStateException("视频提示词长度上限无效");
        if (maxNormalizedBytes < 1024 * 1024 || maxNormalizedBytes > 64L * 1024 * 1024)
            throw new IllegalStateException("生成视频大小上限无效");
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }
    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
    public List<Integer> getSupportedDurationsSeconds() { return supportedDurationsSeconds; }
    public void setSupportedDurationsSeconds(List<Integer> value) { this.supportedDurationsSeconds = value; }
    public BigDecimal getUsdPerSecond() { return usdPerSecond; }
    public void setUsdPerSecond(BigDecimal value) { this.usdPerSecond = value; }
    public Duration getTaskTimeout() { return taskTimeout; }
    public void setTaskTimeout(Duration taskTimeout) { this.taskTimeout = taskTimeout; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public Duration getDownloadTimeout() { return downloadTimeout; }
    public void setDownloadTimeout(Duration downloadTimeout) { this.downloadTimeout = downloadTimeout; }
    public int getMaxScenes() { return maxScenes; }
    public void setMaxScenes(int maxScenes) { this.maxScenes = maxScenes; }
    public int getMaxPromptCharacters() { return maxPromptCharacters; }
    public void setMaxPromptCharacters(int maxPromptCharacters) { this.maxPromptCharacters = maxPromptCharacters; }
    public long getMaxNormalizedBytes() { return maxNormalizedBytes; }
    public void setMaxNormalizedBytes(long maxNormalizedBytes) { this.maxNormalizedBytes = maxNormalizedBytes; }
}
