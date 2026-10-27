package com.link.linkagent.creator.media.preflight.service;

import java.math.BigDecimal;

/**
 * 发布前试映中真实付费调用的费用闸门。
 * <p>
 * 整片试映与已设置制作预算的独立试映进入制作费用台账；没有预算的旧上传流程保留原有调用流水。
 * 调用方必须在发请求前 markSent，用量已知后 settle，响应不明时 unknown，
 * 这样取消、失租和迟到结果都不会漏账或重复扣记。
 */
public interface MediaCallChargeGate {

    Handle reserve(String reviewId, String capability, String chargeKey, BigDecimal usdUpperBound, String priceBasis);

    void settle(String reviewId, String chargeKey, BigDecimal usdActual, String evidenceRef);

    void unknown(String reviewId, String chargeKey);

    /** 该能力单次调用的保守美元上界；没有制作运行归属时返回 0，不参与预留。 */
    BigDecimal upperBound(String capability);

    /** 文本模型按已知 token 换算的实际美元费用；没有配置价格时为空。 */
    BigDecimal estimateTextCost(Long inputTokens, Long outputTokens);

    /** 一次物理调用的发送标记；没有制作运行归属时直接放行。 */
    interface Handle {
        /** false 表示预留已失效或运行已取消，调用方不得发送请求。 */
        boolean markSent();
    }

    Handle NONE = new Handle() {
        @Override public boolean markSent() { return true; }
    };

    MediaCallChargeGate NOOP = new MediaCallChargeGate() {
        @Override public Handle reserve(String reviewId, String capability, String chargeKey,
                                        BigDecimal usdUpperBound, String priceBasis) { return NONE; }
        @Override public void settle(String reviewId, String chargeKey, BigDecimal usdActual, String evidenceRef) { }
        @Override public void unknown(String reviewId, String chargeKey) { }
        @Override public BigDecimal upperBound(String capability) { return BigDecimal.ZERO; }
        @Override public BigDecimal estimateTextCost(Long inputTokens, Long outputTokens) { return BigDecimal.ZERO; }
    };
}
