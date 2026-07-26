package com.link.linkagent.creator.media.processing.service;

import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.processing.model.MediaProcessingEstimate;
import com.link.linkagent.creator.media.processing.model.MediaProcessingOptionsRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 根据用户选项估算抽帧规模和后续 AI 调用成本。
 * P0-2 只做透明估算，不调用视觉模型或 ASR。
 */
@Service
@ConditionalOnProperty(prefix = "creator.media", name = "enabled", havingValue = "true")
public class MediaProcessingCostEstimator {

    private static final int MAX_FRAME_COUNT = 360;

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final String NOTICE = "按当前配置估算，仅供选择处理档位，不代表供应商最终账单";

    private final CreatorMediaProperties mediaProperties;

    public MediaProcessingCostEstimator(CreatorMediaProperties mediaProperties) {
        this.mediaProperties = mediaProperties;
    }

    public MediaProcessingEstimate estimate(long durationMs,
                                             boolean hasAudio,
                                             MediaProcessingOptionsRequest options) {
        long durationSeconds = Math.max(1L, (durationMs + 999L) / 1000L);
        int frameCount = Math.min(MAX_FRAME_COUNT, Math.max(
                1,
                (int) ((durationSeconds + options.frameIntervalSeconds() - 1L)
                        / options.frameIntervalSeconds())
        ));
        long tokensPerFrame = options.resolution().getEstimatedTokensPerFrame();
        long flashInputTokens = frameCount * tokensPerFrame;
        long flashOutputTokens = 1200L;
        int plusReviewFrameCount = options.modelPlan() == MediaProcessingOptionsRequest.ModelPlan.FLASH_PLUS_REVIEW
                ? mediaProperties.getPreflight().getSegmentReviewMaxCount()
                : 0;
        long plusInputTokens = plusReviewFrameCount * tokensPerFrame;
        long plusOutputTokens = plusReviewFrameCount * 800L;

        CreatorMediaProperties.Processing pricing = mediaProperties.getProcessing();
        BigDecimal flashCost = tokenCost(
                flashInputTokens,
                flashOutputTokens,
                pricing.getFlashInputUsdPerMillionTokens(),
                pricing.getFlashOutputUsdPerMillionTokens()
        );
        BigDecimal plusCost = tokenCost(
                plusInputTokens,
                plusOutputTokens,
                pricing.getPlusInputUsdPerMillionTokens(),
                pricing.getPlusOutputUsdPerMillionTokens()
        );
        long asrSeconds = Boolean.TRUE.equals(options.includeAsr()) && hasAudio ? durationSeconds : 0L;
        BigDecimal asrCost = pricing.getAsrUsdPerSecond()
                .multiply(BigDecimal.valueOf(asrSeconds))
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal visualCost = flashCost.add(plusCost).setScale(8, RoundingMode.HALF_UP);

        return new MediaProcessingEstimate(
                pricing.getPricingVersion(),
                durationSeconds,
                frameCount,
                plusReviewFrameCount,
                flashInputTokens + plusInputTokens,
                flashOutputTokens + plusOutputTokens,
                asrSeconds,
                flashCost,
                plusCost,
                visualCost,
                asrCost,
                visualCost.add(asrCost).setScale(8, RoundingMode.HALF_UP),
                plusReviewFrameCount > 0
                        ? "按当前配置估算，Plus 复核按最多 %d 个重点片段计算，实际费用以供应商账单为准"
                                .formatted(plusReviewFrameCount)
                        : NOTICE
        );
    }

    private BigDecimal tokenCost(long inputTokens,
                                 long outputTokens,
                                 BigDecimal inputPrice,
                                 BigDecimal outputPrice) {
        BigDecimal inputCost = BigDecimal.valueOf(inputTokens)
                .multiply(inputPrice)
                .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(outputTokens)
                .multiply(outputPrice)
                .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
        return inputCost.add(outputCost).setScale(8, RoundingMode.HALF_UP);
    }
}
