package com.link.linkagent.creator.creation.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 整片媒体闭环的调度和默认处理档位；与人工入口的媒体配置分开，避免相互影响。 */
@Component
@ConfigurationProperties(prefix = "creator.creation.media")
public class CreationMediaProperties {
    /** 只有显式开启才调度；与媒体链路和固定工程执行器同时开启才可用。 */
    private boolean enabled = false;
    private long pollIntervalMs = 5000;
    private long retryIntervalMs = 30000;
    private int leaseSeconds = 60;
    private int heartbeatSeconds = 15;
    /** 同一阶段含首次在内的领取上限；成功推进阶段后重新计数，不能使用整片累计次数。 */
    private int maxAttempts = 3;
    private int frameIntervalSeconds = 10;
    private String resolution = "P720";
    private String modelPlan = "FLASH_PLUS_REVIEW";
    private boolean includeAsr = true;

    public void validate() {
        if (frameIntervalSeconds < 5 || frameIntervalSeconds > 30) throw new IllegalStateException("自动抽帧间隔必须在5到30秒之间");
        if (!java.util.Set.of("P480", "P720", "P1080").contains(resolution)) throw new IllegalStateException("自动媒体处理清晰度不受支持");
        if (!java.util.Set.of("FLASH", "FLASH_PLUS_REVIEW").contains(modelPlan)) throw new IllegalStateException("自动媒体处理模型档位不受支持");
        if (leaseSeconds < 5 || leaseSeconds > 600) throw new IllegalStateException("媒体闭环执行租约应在5到600秒之间");
        if (heartbeatSeconds < 1 || heartbeatSeconds * 2 > leaseSeconds) throw new IllegalStateException("媒体闭环心跳必须小于租约的一半");
        if (maxAttempts < 1) throw new IllegalStateException("媒体闭环最大尝试次数必须大于0");
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public long getRetryIntervalMs() { return retryIntervalMs; }
    public void setRetryIntervalMs(long retryIntervalMs) { this.retryIntervalMs = retryIntervalMs; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int leaseSeconds) { this.leaseSeconds = leaseSeconds; }
    public int getHeartbeatSeconds() { return heartbeatSeconds; }
    public void setHeartbeatSeconds(int heartbeatSeconds) { this.heartbeatSeconds = heartbeatSeconds; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getFrameIntervalSeconds() { return frameIntervalSeconds; }
    public void setFrameIntervalSeconds(int frameIntervalSeconds) { this.frameIntervalSeconds = frameIntervalSeconds; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public String getModelPlan() { return modelPlan; }
    public void setModelPlan(String modelPlan) { this.modelPlan = modelPlan; }
    public boolean isIncludeAsr() { return includeAsr; }
    public void setIncludeAsr(boolean includeAsr) { this.includeAsr = includeAsr; }
}
