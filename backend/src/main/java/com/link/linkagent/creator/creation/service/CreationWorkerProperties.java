package com.link.linkagent.creator.creation.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 首发固定一个执行槽，避免本机媒体合成挤占所有后台资源。 */
@Component
@ConfigurationProperties(prefix = "creator.creation.worker")
public class CreationWorkerProperties {
    private boolean enabled;
    private int leaseSeconds = 30;
    private int heartbeatSeconds = 5;
    private int maxAttempts = 3;

    public void validate() {
        if (leaseSeconds < 5 || leaseSeconds > 600 || heartbeatSeconds < 1
                || heartbeatSeconds * 3L > leaseSeconds || maxAttempts < 1 || maxAttempts > 10)
            throw new IllegalStateException("制作执行器租约、心跳或接管次数配置无效");
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int value) { leaseSeconds = value; }
    public int getHeartbeatSeconds() { return heartbeatSeconds; }
    public void setHeartbeatSeconds(int value) { heartbeatSeconds = value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { maxAttempts = value; }
}
