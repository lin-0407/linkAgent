package com.link.linkagent.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 跨分区视频案例质量打分配置（阶段 5.1c）。
 * <p>
 * 把 §2.7 质量公式里所有「可调数值」集中到这里：公式形态是固定的（互动率 → 加权对数 → 情绪因子 → 分区归一化），
 * 但权重和常数需要等真实排行榜数据采回后反推标定，所以做成配置而非散落在代码里的魔法数，便于不改代码就能调参。
 * <p>
 * 这是一套纯计算配置，不依赖 Milvus / Embedding，与 {@code knowledge.rag} 开关无关：即使向量库全关，打分照常工作。
 */
@Component
@ConfigurationProperties(prefix = "knowledge.quality")
public class KnowledgeQualityProperties {

    /**
     * 投币率权重。投币是 B 站最强的质量信号（要消耗硬币、门槛最高），故权重最大。
     */
    private double coinWeight = 0.40;

    /**
     * 收藏率权重。收藏代表「值得回看」，质量信号仅次于投币。
     */
    private double favoriteWeight = 0.25;

    /**
     * 点赞率权重。点赞门槛低、信号偏弱，权重居中。
     */
    private double likeWeight = 0.20;

    /**
     * 评论率权重。评论代表愿意表达，但有正有负，单独看不能区分好坏，权重偏低。
     */
    private double replyWeight = 0.10;

    /**
     * 弹幕率权重。弹幕更多反映「热闹」而非「认可」，质量信号最弱，权重最低。
     * 五项权重之和 = 1，保证加权互动分量纲稳定。
     */
    private double danmakuWeight = 0.05;

    /**
     * 互动率对数缩放系数。B 站互动「率」普遍很小（千分位级别），先乘以该系数再取对数，
     * 让数值落进合适区间；取对数本身用于压制头部爆款的长尾，避免极端值碾压同分区其它视频。
     */
    private double logScale = 1000.0;

    /**
     * 情绪置信度平滑常数 K。confidence = n / (n + K)：样本越多越接近 1，样本少时收敛到 0，
     * 从而把「只有 2 条评论却全是好评」这种小样本极端比例拉回中性，避免失真。K=10 是经验默认值。
     */
    private double sentimentSmoothingK = 10.0;

    /**
     * 情绪乘子基准（全差评时的乘子）。sentimentMul = base + span * factor，factor ∈ [0,1]。
     */
    private double sentimentMulBase = 0.7;

    /**
     * 情绪乘子跨度。base=0.7、span=0.6 → 乘子区间 [0.7, 1.3]：0.7=全差评、1.0=中性、1.3=全好评。
     */
    private double sentimentMulSpan = 0.6;

    /**
     * 最小可靠样本数。分区相对分依赖 min-max 归一化，样本太少时会出现 60 / 0 / 100 的跳变，
     * 所以低于该数量时只保存 raw_quality_score，不产出可展示的 quality_score。
     */
    private int minReliableSampleSize = 5;

    public double getCoinWeight() {
        return coinWeight;
    }

    public void setCoinWeight(double coinWeight) {
        this.coinWeight = coinWeight;
    }

    public double getFavoriteWeight() {
        return favoriteWeight;
    }

    public void setFavoriteWeight(double favoriteWeight) {
        this.favoriteWeight = favoriteWeight;
    }

    public double getLikeWeight() {
        return likeWeight;
    }

    public void setLikeWeight(double likeWeight) {
        this.likeWeight = likeWeight;
    }

    public double getReplyWeight() {
        return replyWeight;
    }

    public void setReplyWeight(double replyWeight) {
        this.replyWeight = replyWeight;
    }

    public double getDanmakuWeight() {
        return danmakuWeight;
    }

    public void setDanmakuWeight(double danmakuWeight) {
        this.danmakuWeight = danmakuWeight;
    }

    public double getLogScale() {
        return logScale;
    }

    public void setLogScale(double logScale) {
        this.logScale = logScale;
    }

    public double getSentimentSmoothingK() {
        return sentimentSmoothingK;
    }

    public void setSentimentSmoothingK(double sentimentSmoothingK) {
        this.sentimentSmoothingK = sentimentSmoothingK;
    }

    public double getSentimentMulBase() {
        return sentimentMulBase;
    }

    public void setSentimentMulBase(double sentimentMulBase) {
        this.sentimentMulBase = sentimentMulBase;
    }

    public double getSentimentMulSpan() {
        return sentimentMulSpan;
    }

    public void setSentimentMulSpan(double sentimentMulSpan) {
        this.sentimentMulSpan = sentimentMulSpan;
    }

    public int getMinReliableSampleSize() {
        return minReliableSampleSize;
    }

    public void setMinReliableSampleSize(int minReliableSampleSize) {
        this.minReliableSampleSize = minReliableSampleSize;
    }
}
