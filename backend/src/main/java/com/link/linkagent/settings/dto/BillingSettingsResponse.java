package com.link.linkagent.settings.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 汇率与付费能力参考。
 * source 取值 LIVE / CACHED / CONFIG_FALLBACK；fetchedAt 是本地成功获取时间，不是供应商账单时间。
 */
public record BillingSettingsResponse(
        BigDecimal usdToCny,
        String source,
        String fetchedAt,
        boolean stale,
        String note,
        List<PriceRangeResponse> ranges
) {
}
