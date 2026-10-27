package com.link.linkagent.settings.dto;

import java.math.BigDecimal;

/**
 * 单项付费能力的粗略费用区间。
 * 只按部署配置的单价和当前汇率换算，不代表供应商账单；上限是配置单价的 1.5 倍。
 */
public record PriceRangeResponse(
        String key,
        String name,
        String unit,
        BigDecimal lowCny,
        BigDecimal highCny,
        String note
) {
}
