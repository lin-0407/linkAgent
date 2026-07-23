package com.link.linkagent.creator.media.processing.model;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 用户确认的媒体预处理和后续 AI 成本估算选项。
 */
public record MediaProcessingOptionsRequest(
        @NotNull(message = "抽帧间隔不能为空")
        @Min(value = 5, message = "抽帧间隔不能小于5秒")
        @Max(value = 30, message = "抽帧间隔不能大于30秒")
        Integer frameIntervalSeconds,
        @NotNull(message = "清晰度不能为空")
        Resolution resolution,
        @NotNull(message = "模型方案不能为空")
        ModelPlan modelPlan,
        @NotNull(message = "ASR选项不能为空")
        Boolean includeAsr
) {

    /**
     * P0-2 只开放四个经过页面说明的档位，避免任意间隔造成不可预测的图片数量。
     */
    @AssertTrue(message = "抽帧间隔只允许5、10、15或30秒")
    public boolean isFrameIntervalSupported() {
        return frameIntervalSeconds == null
                || frameIntervalSeconds == 5
                || frameIntervalSeconds == 10
                || frameIntervalSeconds == 15
                || frameIntervalSeconds == 30;
    }

    public enum Resolution {
        P480(480, 854, 450L),
        P720(720, 1280, 850L),
        P1080(1080, 1920, 1450L);

        private final int height;
        private final int width;
        private final long estimatedTokensPerFrame;

        Resolution(int height, int width, long estimatedTokensPerFrame) {
            this.height = height;
            this.width = width;
            this.estimatedTokensPerFrame = estimatedTokensPerFrame;
        }

        public int getHeight() { return height; }
        public int getWidth() { return width; }
        public long getEstimatedTokensPerFrame() { return estimatedTokensPerFrame; }
    }

    public enum ModelPlan {
        FLASH,
        FLASH_PLUS_REVIEW
    }
}
