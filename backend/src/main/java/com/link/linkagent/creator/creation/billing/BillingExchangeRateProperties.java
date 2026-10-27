package com.link.linkagent.creator.creation.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 实时汇率配置。
 * 默认使用无需密钥的公开参考汇率；部署可以换成自己的行情网关。
 * 上游通常是每日参考价，设置页会同时显示“获取时间”和“是否过期”，不把它描述成银行成交价。
 */
@Component
@ConfigurationProperties(prefix = "creator.creation.billing.fx")
public class BillingExchangeRateProperties {

    private boolean enabled = true;
    private String url = "https://open.er-api.com/v6/latest/USD";
    private Duration refreshInterval = Duration.ofMinutes(30);
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(3);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public Duration getRefreshInterval() { return refreshInterval; }
    public void setRefreshInterval(Duration refreshInterval) { this.refreshInterval = refreshInterval; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
}
