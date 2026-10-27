package com.link.linkagent.creator.creation.voice.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 配音准备的部署配置。
 * <p>
 * 价格只是记账上界，不是供应商账单；密钥沿用媒体模块已配置的 DashScope 凭据，
 * 这里只保存模型、音色和调用节奏，避免出现第二份密钥配置。
 */
@Component
@ConfigurationProperties(prefix = "creator.creation.voice")
public class CreationVoiceProperties {
    /** 未显式开启时不产生任何付费调用，估值接口仍可返回待配置原因。 */
    private boolean enabled = false;
    /** DashScope CosyVoice 兼容模型；具体型号由部署自行确认后填写。 */
    private String model = "cosyvoice-v2";
    private String voice = "longxiaochun_v2";
    /** 只接受 WAV，生成的字节需要能按 16 位 PCM 规范化并复核。 */
    private String format = "wav";
    private int sampleRate = 24000;
    /** 合成计费单价上界（美元/百万字符），写入价格快照，不冒充真实账单。 */
    private BigDecimal usdPerMillionCharacters = new BigDecimal("28.00");
    /** 文稿与转写文本的最低确定性相似度；低于该值必须交给作者确认。 */
    private BigDecimal matchThreshold = new BigDecimal("0.85");
    /** 单次合成请求的超时，避免外部调用长期占住执行线程。 */
    private Duration requestTimeout = Duration.ofSeconds(60);
    /** 单次转写提交后的最长等待；超时保留任务标识并交给作者处理，不重复提交。 */
    private Duration transcribeTimeout = Duration.ofMinutes(5);
    /** 转写状态轮询间隔。 */
    private Duration transcribePollInterval = Duration.ofSeconds(2);
    /** 单场景文稿上限与场景数量上限，防止一次授权覆盖不可控的调用量。 */
    private int maxSceneCharacters = 1000;
    private int maxScenes = 48;

    /** 供应商参数是否完整；运行期开关单独由设置面板控制。 */
    public boolean providerConfigured() {
        return model != null && !model.isBlank() && voice != null && !voice.isBlank()
                && "wav".equalsIgnoreCase(format) && sampleRate >= 8000 && sampleRate <= 48000
                && usdPerMillionCharacters != null && usdPerMillionCharacters.signum() > 0;
    }

    /** 部署默认值；设置面板的覆盖值优先，不能再用它判断当前是否可用。 */
    public boolean configured() {
        return enabled && providerConfigured();
    }

    public void validate() {
        if (model == null || model.isBlank() || voice == null || voice.isBlank())
            throw new IllegalStateException("配音模型与音色不能为空");
        if (!"wav".equalsIgnoreCase(format)) throw new IllegalStateException("当前只支持 WAV 配音输出");
        if (sampleRate < 8000 || sampleRate > 48000) throw new IllegalStateException("配音采样率超出允许范围");
        if (usdPerMillionCharacters == null || usdPerMillionCharacters.signum() <= 0)
            throw new IllegalStateException("配音单价上界必须大于0");
        if (matchThreshold == null || matchThreshold.signum() < 0 || matchThreshold.compareTo(BigDecimal.ONE) > 0)
            throw new IllegalStateException("文稿比对阈值必须在0到1之间");
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                || transcribeTimeout == null || transcribeTimeout.isNegative() || transcribeTimeout.isZero()
                || transcribePollInterval == null || transcribePollInterval.isNegative() || transcribePollInterval.isZero())
            throw new IllegalStateException("配音调用超时配置无效");
        if (maxSceneCharacters < 1 || maxSceneCharacters > 2000 || maxScenes < 1 || maxScenes > 48)
            throw new IllegalStateException("配音场景数量或文稿长度上限无效");
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getVoice() { return voice; }
    public void setVoice(String voice) { this.voice = voice; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public int getSampleRate() { return sampleRate; }
    public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }
    public BigDecimal getUsdPerMillionCharacters() { return usdPerMillionCharacters; }
    public void setUsdPerMillionCharacters(BigDecimal value) { this.usdPerMillionCharacters = value; }
    public BigDecimal getMatchThreshold() { return matchThreshold; }
    public void setMatchThreshold(BigDecimal value) { this.matchThreshold = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public Duration getTranscribeTimeout() { return transcribeTimeout; }
    public void setTranscribeTimeout(Duration transcribeTimeout) { this.transcribeTimeout = transcribeTimeout; }
    public Duration getTranscribePollInterval() { return transcribePollInterval; }
    public void setTranscribePollInterval(Duration value) { this.transcribePollInterval = value; }
    public int getMaxSceneCharacters() { return maxSceneCharacters; }
    public void setMaxSceneCharacters(int maxSceneCharacters) { this.maxSceneCharacters = maxSceneCharacters; }
    public int getMaxScenes() { return maxScenes; }
    public void setMaxScenes(int maxScenes) { this.maxScenes = maxScenes; }
}
