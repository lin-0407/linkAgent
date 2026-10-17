package com.link.linkagent.knowledge.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 各互动率先与本分区中位数比较，再加权修正基础指数，避免原始比率量级替代设定权重。 */
@Component
@Validated
@ConfigurationProperties(prefix = "knowledge.quality")
public class KnowledgeQualityProperties {
    @DecimalMin("0") @DecimalMax("1")
    private double coinWeight = 0.40;
    @DecimalMin("0") @DecimalMax("1")
    private double favoriteWeight = 0.25;
    @DecimalMin("0") @DecimalMax("1")
    private double likeWeight = 0.20;
    @DecimalMin("0") @DecimalMax("1")
    private double replyWeight = 0.10;
    @DecimalMin("0") @DecimalMax("1")
    private double danmakuWeight = 0.05;
    /** 限制同分区比较对原有脚本指数的改动，避免几个对手决定视频的全部分数。 */
    @DecimalMin("0") @DecimalMax("100")
    private double maxCategoryAdjustment = 5;

    @AssertTrue(message = "五项互动权重必须是有限非负数且总和为1")
    public boolean isWeightSumValid() {
        double sum = coinWeight + favoriteWeight + likeWeight + replyWeight + danmakuWeight;
        return Double.isFinite(sum) && Math.abs(sum - 1) < 1e-9;
    }

    public double getCoinWeight() { return coinWeight; }
    public void setCoinWeight(double value) { coinWeight = value; }
    public double getFavoriteWeight() { return favoriteWeight; }
    public void setFavoriteWeight(double value) { favoriteWeight = value; }
    public double getLikeWeight() { return likeWeight; }
    public void setLikeWeight(double value) { likeWeight = value; }
    public double getReplyWeight() { return replyWeight; }
    public void setReplyWeight(double value) { replyWeight = value; }
    public double getDanmakuWeight() { return danmakuWeight; }
    public void setDanmakuWeight(double value) { danmakuWeight = value; }
    public double getMaxCategoryAdjustment() { return maxCategoryAdjustment; }
    public void setMaxCategoryAdjustment(double value) { maxCategoryAdjustment = value; }
}
