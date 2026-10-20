package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.mapper.CreationMediaMapper;
import com.link.linkagent.creator.creation.model.CreationMediaLink;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Charge;
import com.link.linkagent.creator.media.preflight.service.MediaCallChargeGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import static com.link.linkagent.creator.creation.service.CreationInputRules.*;

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
    private record Price(BigDecimal usdUpperBound, BigDecimal usdToCny, String capability, String basis) { }

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
        var previous = runtime.findCharge(link.taskId(), link.ownerId(), link.runId(), chargeKey);
        if (previous.isPresent()) {
            var charge = previous.get(); var frozen = frozenPrice(link, charge);
            if (!capability.equals(charge.capability()) || !capability.equals(frozen.capability())
                    || usdUpperBound.compareTo(frozen.usdUpperBound()) != 0
                    || !basis(priceBasis).equals(frozen.basis())) throw conflict("调用标识已经用于其他费用预留");
            // 重启或配置变更不能改变同一物理调用；让原运行闸门继续校验原金额和原快照。
            runtime.reserveDependency(link.taskId(), link.ownerId(), link.runId(), chargeKey, capability,
                    charge.reservedCny(), charge.priceSnapshotJson());
        } else {
            String snapshot = price(capability, usdUpperBound, priceBasis);
            var frozen = readPrice(snapshot);
            runtime.reserveDependency(link.taskId(), link.ownerId(), link.runId(), chargeKey, capability,
                    reservationCny(usdUpperBound, frozen.usdToCny()), snapshot);
        }
        return () -> runtime.markSentDependency(link.taskId(), link.ownerId(), chargeKey);
    }

    @Override
    public void settle(String reviewId, String chargeKey, BigDecimal usdActual, String evidenceRef) {
        var link = links.byReview(reviewId).orElse(null);
        if (link == null) return;
        if (usdActual == null) { unknown(reviewId, chargeKey); return; }
        var charge = runtime.findCharge(link.taskId(), link.ownerId(), link.runId(), chargeKey).orElse(null);
        if (charge == null) {
            // 只把确实查不到预留的旧调用视为历史缺口，金额冲突、归属错误等不能被吞掉。
            log.warn("试映调用没有可结算的费用预留 reviewId={}", reviewId);
            return;
        }
        var frozen = frozenPrice(link, charge);
        if (usdActual.signum() < 0) throw conflict("实际美元费用不能为负");
        runtime.settle(link.taskId(), link.ownerId(), chargeKey, toCny(usdActual, frozen.usdToCny()), "USAGE_ESTIMATE",
                evidence(evidenceRef));
    }

    @Override
    public void unknown(String reviewId, String chargeKey) {
        var link = links.byReview(reviewId).orElse(null);
        if (link == null) return;
        if (runtime.findCharge(link.taskId(), link.ownerId(), link.runId(), chargeKey).isEmpty()) {
            log.warn("试映调用无法标记费用未知 reviewId={}", reviewId);
            return;
        }
        runtime.markUnknown(link.taskId(), link.ownerId(), chargeKey);
    }

    /** 文本模型仅按配置单价上界估算美元费用，结算类型仍为 USAGE_ESTIMATE。 */
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

    private BigDecimal toCny(BigDecimal usd, BigDecimal rate) {
        return usd.multiply(rate).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal reservationCny(BigDecimal usd, BigDecimal rate) {
        BigDecimal cny = toCny(usd, rate);
        // 台账不接受零金额预留；极小的美元上界按最小可记账金额保留，结算后按实际费用回落。
        return cny.signum() == 0 ? new BigDecimal("0.000001") : cny;
    }

    private String price(String capability, BigDecimal usdUpperBound, String basis) {
        ObjectNode node = json.createObjectNode();
        node.put("currency", "USD").put("usdUpperBound", usdUpperBound.toPlainString())
                .put("usdToCny", properties.getUsdToCny().toPlainString())
                .put("capability", capability).put("basis", basis(basis)).put("accounting", "USAGE_ESTIMATE");
        return node.toString();
    }

    private Price frozenPrice(CreationMediaLink link, Charge charge) {
        try {
            var price = readPrice(charge.priceSnapshotJson());
            if (!charge.capability().equals(price.capability())) throw new IllegalArgumentException("快照与费用能力不一致");
            return price;
        }
        catch (IllegalArgumentException | org.springframework.web.server.ResponseStatusException exception) {
            // 已发送的事实不能用现在的汇率补猜；未知占用持续保留，等待原始价格依据核实。
            if (charge.sentAt() != null) runtime.markUnknown(link.taskId(), link.ownerId(), charge.chargeKey());
            throw conflict("费用价格快照缺失或无效，请核对该调用的原始记账依据");
        }
    }

    private Price readPrice(String snapshot) {
        try {
            var node = json.readTree(canonical(json, snapshot));
            if (!"USD".equals(node.path("currency").asText()) || !node.path("capability").isTextual()
                    || node.path("capability").asText().isBlank() || !node.path("basis").isTextual())
                throw new IllegalArgumentException("价格快照字段不完整");
            BigDecimal rate = decimal(node.get("usdToCny"));
            BigDecimal upper = decimal(node.get("usdUpperBound"));
            if (rate.signum() <= 0 || upper.signum() < 0) throw new IllegalArgumentException("价格快照金额无效");
            return new Price(upper, rate, node.path("capability").asText(), node.path("basis").asText());
        } catch (java.io.IOException exception) { throw new IllegalArgumentException("价格快照不是有效JSON", exception); }
    }

    private BigDecimal decimal(com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null || (!value.isNumber() && !value.isTextual())) throw new IllegalArgumentException("价格快照数字缺失");
        BigDecimal number = new BigDecimal(value.asText());
        if (Math.abs((long) number.scale()) > 18 || (long) number.precision() - number.scale() > 12)
            throw new IllegalArgumentException("价格快照数字超出记账范围");
        return number;
    }

    private static String basis(String value) { return value == null ? "" : value; }

    private static String evidence(String value) {
        String safe = value == null || value.isBlank() ? "media-call" : value.replaceAll("[^A-Za-z0-9_.:-]", "-");
        return safe.length() > 128 ? safe.substring(0, 128) : safe;
    }
}
