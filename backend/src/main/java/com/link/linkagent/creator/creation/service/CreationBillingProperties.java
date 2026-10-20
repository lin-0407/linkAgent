package com.link.linkagent.creator.creation.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 制作费用台账的计价口径。
 * <p>
 * 媒体供应商按美元计价，制作预算按人民币记账，因此必须显式配置折算率并写进价格快照；
 * 这里的数值是记账口径，不是实时汇率，也不代表供应商最终账单。
 */
@Component
@ConfigurationProperties(prefix = "creator.creation.billing")
public class CreationBillingProperties {
    /** 美元到人民币的记账折算率；调整只影响之后预留与结算，历史流水保留当时的快照。 */
    private BigDecimal usdToCny = new BigDecimal("7.20");
    /** 单次重点片段复核的保守上界，用于发送前预留。 */
    private BigDecimal segmentReviewUsdUpperBound = new BigDecimal("0.05");
    /** 单次全片视频理解的保守上界；预处理已给出预估时优先使用预估。 */
    private BigDecimal videoUsdUpperBound = new BigDecimal("0.30");
    /** 单次观众试映的保守上界，用于发送前预留。 */
    private BigDecimal textScreeningUsdUpperBound = new BigDecimal("0.05");
    /** 文本模型输入单价上界，仅用于把已知 token 换算成可核对的实际费用。 */
    private BigDecimal textInputUsdPerMillionTokens = new BigDecimal("0.20");
    /** 文本模型输出单价上界。 */
    private BigDecimal textOutputUsdPerMillionTokens = new BigDecimal("0.80");

    public void validate() {
        if (usdToCny == null || usdToCny.signum() <= 0) throw new IllegalStateException("美元折算率必须大于0");
        if (segmentReviewUsdUpperBound == null || segmentReviewUsdUpperBound.signum() <= 0)
            throw new IllegalStateException("重点片段复核预留必须大于0");
        if (videoUsdUpperBound == null || videoUsdUpperBound.signum() <= 0)
            throw new IllegalStateException("视频理解预留必须大于0");
        if (textScreeningUsdUpperBound == null || textScreeningUsdUpperBound.signum() <= 0)
            throw new IllegalStateException("观众试映预留必须大于0");
        if (textInputUsdPerMillionTokens == null || textInputUsdPerMillionTokens.signum() < 0
                || textOutputUsdPerMillionTokens == null || textOutputUsdPerMillionTokens.signum() < 0)
            throw new IllegalStateException("文本模型单价不能为负");
    }

    public BigDecimal getUsdToCny() { return usdToCny; }
    public void setUsdToCny(BigDecimal usdToCny) { this.usdToCny = usdToCny; }
    public BigDecimal getSegmentReviewUsdUpperBound() { return segmentReviewUsdUpperBound; }
    public void setSegmentReviewUsdUpperBound(BigDecimal value) { this.segmentReviewUsdUpperBound = value; }
    public BigDecimal getVideoUsdUpperBound() { return videoUsdUpperBound; }
    public void setVideoUsdUpperBound(BigDecimal value) { this.videoUsdUpperBound = value; }
    public BigDecimal getTextScreeningUsdUpperBound() { return textScreeningUsdUpperBound; }
    public void setTextScreeningUsdUpperBound(BigDecimal value) { this.textScreeningUsdUpperBound = value; }
    public BigDecimal getTextInputUsdPerMillionTokens() { return textInputUsdPerMillionTokens; }
    public void setTextInputUsdPerMillionTokens(BigDecimal value) { this.textInputUsdPerMillionTokens = value; }
    public BigDecimal getTextOutputUsdPerMillionTokens() { return textOutputUsdPerMillionTokens; }
    public void setTextOutputUsdPerMillionTokens(BigDecimal value) { this.textOutputUsdPerMillionTokens = value; }
}
