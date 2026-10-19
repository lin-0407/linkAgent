package com.link.linkagent.creator.media.processing.service;

import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.creator.media.processing.model.MediaProcessingOptionsRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-2 成本估算测试，确保页面显示的图片数、Token 和 ASR 时长与实际处理上限一致。
 */
class MediaProcessingCostEstimatorTest {

    @Test
    void shouldCapFramesAndEstimatePlusReviewAndAsr() {
        MediaProcessingOptionsRequest options = new MediaProcessingOptionsRequest(
                5,
                MediaProcessingOptionsRequest.Resolution.P1080,
                MediaProcessingOptionsRequest.ModelPlan.FLASH_PLUS_REVIEW,
                true
        );

        var estimate = estimator().estimate(3_600_000L, true, options);

        assertThat(estimate.estimatedFrameCount()).isEqualTo(360);
        assertThat(estimate.plusReviewFrameCount()).isEqualTo(5);
        assertThat(estimate.estimatedVisualOutputTokens()).isEqualTo(5_200L);
        assertThat(estimate.estimatedAsrSeconds()).isEqualTo(3600L);
        assertThat(estimate.estimatedVisualInputTokens()).isPositive();
        assertThat(estimate.estimatedTotalCostUsd()).isPositive();
    }

    @Test
    void shouldNotEstimateAsrWhenVideoHasNoAudio() {
        MediaProcessingOptionsRequest options = new MediaProcessingOptionsRequest(
                10,
                MediaProcessingOptionsRequest.Resolution.P480,
                MediaProcessingOptionsRequest.ModelPlan.FLASH,
                true
        );

        var estimate = estimator().estimate(60_000L, false, options);

        assertThat(estimate.estimatedFrameCount()).isEqualTo(6);
        assertThat(estimate.estimatedAsrSeconds()).isZero();
        assertThat(estimate.estimatedAsrCostUsd()).isZero();
    }

    @Test
    void shouldUsePlusPricingForBothGlobalAnalysisAndSegmentReview() {
        CreatorMediaProperties properties = new CreatorMediaProperties();
        // 把旧 Flash 单价设得明显不同，防止全片分析继续误用旧价格。
        properties.getProcessing().setFlashInputUsdPerMillionTokens(new java.math.BigDecimal("100"));
        properties.getProcessing().setFlashOutputUsdPerMillionTokens(new java.math.BigDecimal("200"));
        properties.getProcessing().setPlusInputUsdPerMillionTokens(new java.math.BigDecimal("2"));
        properties.getProcessing().setPlusOutputUsdPerMillionTokens(new java.math.BigDecimal("4"));
        var options = new MediaProcessingOptionsRequest(10,
                MediaProcessingOptionsRequest.Resolution.P480,
                MediaProcessingOptionsRequest.ModelPlan.FLASH_PLUS_REVIEW, false);

        var estimate = new MediaProcessingCostEstimator(properties).estimate(60000L, true, options);

        assertThat(estimate.estimatedFlashCostUsd()).isEqualByComparingTo("0.0102");
        assertThat(estimate.estimatedPlusCostUsd()).isEqualByComparingTo("0.0205");
        assertThat(estimate.estimatedVisualCostUsd()).isEqualByComparingTo("0.0307");
    }

    private MediaProcessingCostEstimator estimator() {
        return new MediaProcessingCostEstimator(new CreatorMediaProperties());
    }
}
