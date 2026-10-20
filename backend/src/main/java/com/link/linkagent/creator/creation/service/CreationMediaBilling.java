package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.mapper.CreationMediaMapper;
import com.link.linkagent.creator.media.preflight.service.MediaCallChargeGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 把发布前试映里真实付费的媒体调用接到制作费用台账。
 * <p>
 * 只有属于整片运行的试映才记账；人工试映保持原有的调用流水。美元用量按配置的记账折算率换成人民币，
 * 折算率、美元上界和价格依据都写入 price_snapshot_json，事后可以核对当时的口径。
 */
@Service
public class CreationMediaBilling implements MediaCallChargeGate {
    private static final Logger log = LoggerFactory.getLogger(CreationMediaBilling.class);
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    private final CreationMediaMapper links;
    private final CreationRuntimeService runtime;
    private final CreationBillingProperties properties;
    private final ObjectMapper json;

    public CreationMediaBilling(CreationMediaMapper links, CreationRuntimeService runtime,
                                CreationBillingProperties properties, ObjectMapper json) {
        properties.validate();
        this.links = links; this.runtime = runtime; this.properties = properties; this.json = json;
    }

    @Override
    public Handle reserve(String reviewId, String capability, String chargeKey, BigDecimal usdUpperBound,
                          String priceBasis) {
        var link = links.byReview(reviewId).orElse(null);
        if (link == null) return MediaCallChargeGate.NONE;
        if (usdUpperBound == null) throw new IllegalArgumentException("缺少本次调用的费用上界");
        if (usdUpperBound.signum() < 0) throw new IllegalArgumentException("费用上界不能为负");
        BigDecimal cny = toCny(usdUpperBound);
        runtime.reserveDependency(link.taskId(), link.ownerId(), link.runId(), chargeKey, capability, cny,
                price(capability, usdUpperBound, priceBasis));
        return () -> runtime.markSentDependency(link.taskId(), link.ownerId(), chargeKey);
    }

    @Override
    public void settle(String reviewId, String chargeKey, BigDecimal usdActual, String evidenceRef) {
        var link = links.byReview(reviewId).orElse(null);
        if (link == null) return;
        if (usdActual == null) { unknown(reviewId, chargeKey); return; }
        try {
            runtime.settle(link.taskId(), link.ownerId(), chargeKey, toCny(usdActual), "USAGE_ESTIMATE",
                    evidence(evidenceRef));
        } catch (ResponseStatusException exception) {
            // 升级前已提交的调用没有预留，属于历史缺口；记录后不阻断结果保存。
            log.warn("试映调用没有可结算的费用预留 reviewId={}", reviewId);
        }
    }

    @Override
    public void unknown(String reviewId, String chargeKey) {
        var link = links.byReview(reviewId).orElse(null);
        if (link == null) return;
        try {
            runtime.markUnknown(link.taskId(), link.ownerId(), chargeKey);
        } catch (ResponseStatusException exception) {
            log.warn("试映调用无法标记费用未知 reviewId={}", reviewId);
        }
    }

    /** 文本模型没有供应商价格，按配置的上界把已知 token 换算成可核对的实际费用。 */
    @Override
    public BigDecimal estimateTextCost(Long inputTokens, Long outputTokens) {
        if (inputTokens == null && outputTokens == null) return null;
        BigDecimal input = BigDecimal.valueOf(inputTokens == null ? 0L : inputTokens)
                .multiply(properties.getTextInputUsdPerMillionTokens()).divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
        BigDecimal output = BigDecimal.valueOf(outputTokens == null ? 0L : outputTokens)
                .multiply(properties.getTextOutputUsdPerMillionTokens()).divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
        return input.add(output).setScale(8, RoundingMode.HALF_UP);
    }

    public BigDecimal segmentUpperBound() { return properties.getSegmentReviewUsdUpperBound(); }

    public BigDecimal textUpperBound() { return properties.getTextScreeningUsdUpperBound(); }

    @Override
    public BigDecimal upperBound(String capability) {
        return switch (capability) {
            case "MEDIA_SEGMENT" -> properties.getSegmentReviewUsdUpperBound();
            case "MEDIA_VIDEO" -> properties.getVideoUsdUpperBound();
            case "MEDIA_TEXT_SCREENING" -> properties.getTextScreeningUsdUpperBound();
            default -> BigDecimal.ZERO;
        };
    }

    private BigDecimal toCny(BigDecimal usd) {
        BigDecimal cny = usd.multiply(properties.getUsdToCny()).setScale(6, RoundingMode.HALF_UP);
        // 台账不接受零金额预留；极小的美元上界按最小可记账金额保留，结算后按实际费用回落。
        return cny.signum() == 0 ? new BigDecimal("0.000001") : cny;
    }

    private String price(String capability, BigDecimal usdUpperBound, String basis) {
        ObjectNode node = json.createObjectNode();
        node.put("currency", "USD").put("usdUpperBound", usdUpperBound.setScale(8, RoundingMode.HALF_UP).toPlainString())
                .put("usdToCny", properties.getUsdToCny().toPlainString())
                .put("capability", capability).put("basis", basis == null ? "" : basis);
        return node.toString();
    }

    private static String evidence(String value) {
        String safe = value == null || value.isBlank() ? "media-call" : value.replaceAll("[^A-Za-z0-9_.:-]", "-");
        return safe.length() > 128 ? safe.substring(0, 128) : safe;
    }
}
