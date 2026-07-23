package com.link.linkagent.creator.media.processing.model;

import java.math.BigDecimal;

/**
 * 本地预处理规模和后续 AI 调用费用估算。
 * 所有金额只代表当前配置计算结果，不代表供应商最终账单。
 */
public record MediaProcessingEstimate(
        String pricingVersion,
        long durationSeconds,
        int estimatedFrameCount,
        int plusReviewFrameCount,
        long estimatedVisualInputTokens,
        long estimatedVisualOutputTokens,
        long estimatedAsrSeconds,
        BigDecimal estimatedFlashCostUsd,
        BigDecimal estimatedPlusCostUsd,
        BigDecimal estimatedVisualCostUsd,
        BigDecimal estimatedAsrCostUsd,
        BigDecimal estimatedTotalCostUsd,
        String notice
) {
}
