package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeQualityProperties;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoScoringRow;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 视频案例质量打分服务（阶段 5.1c）。
 * <p>
 * 实现 §2.7 的质量公式 v1：互动率 → 加权对数互动分 → 情绪因子乘子 → 按分区 min-max 归一化到 0–100。
 * 之所以「按分区」归一化：不同分区的互动率基线差异极大（知识区和娱乐区不可直接比），分区内相对排名才有意义。
 * <p>
 * 纯 SQL + 数学计算，不依赖 Milvus / Embedding。打分时机是「导入后按受影响分区重算」，
 * 因为归一化是分区相对值——新增一条样例会改变该分区的 min/max，必须连带刷新同分区其它视频的分数。
 */
@Service
public class KnowledgeQualityScoringService {

    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;
    private final KnowledgeQualityProperties knowledgeQualityProperties;

    public KnowledgeQualityScoringService(KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                          KnowledgeQualityProperties knowledgeQualityProperties) {
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
        this.knowledgeQualityProperties = knowledgeQualityProperties;
    }

    /**
     * 重算一批分区的质量分。
     * 加事务：单独调用时让「一个分区的全部回写」原子化；被导入流程（已在事务中）调用时按 REQUIRED 合并进同一事务，
     * 从而能读到刚插入、尚未提交的新视频。空白 / null 分区直接跳过——按设计这些视频不参与分区相对打分。
     */
    @Transactional
    public void recomputeCategories(Collection<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return;
        }
        Set<String> distinctCategories = new LinkedHashSet<>();
        for (String category : categories) {
            String normalized = TextUtil.trimToNull(category);
            if (normalized != null) {
                distinctCategories.add(normalized);
            }
        }
        for (String category : distinctCategories) {
            recomputeCategory(category);
        }
    }

    /**
     * 重算单个分区：取出该分区全部未删除视频的热度 + 情绪样本，算出各自原始分后做 min-max 归一化回写。
     * 只对「可打分」（view 有效）的视频参与 min/max；view 缺失 / 为 0 的视频原始分为 null，归一化后写回 NULL。
     */
    private void recomputeCategory(String category) {
        List<ReferenceVideoScoringRow> rows =
                knowledgeReferenceVideoMapper.listScoringRowsByCategory(category);
        if (rows.isEmpty()) {
            return;
        }

        // 先算原始分（可能为 null），同时收集可打分视频的 min/max，供下一步归一化
        Map<String, Double> rawScores = new LinkedHashMap<>();
        Double minRaw = null;
        Double maxRaw = null;
        for (ReferenceVideoScoringRow row : rows) {
            Double rawScore = computeRawScore(row);
            rawScores.put(row.getVideoId(), rawScore);
            if (rawScore != null) {
                minRaw = (minRaw == null) ? rawScore : Math.min(minRaw, rawScore);
                maxRaw = (maxRaw == null) ? rawScore : Math.max(maxRaw, rawScore);
            }
        }

        // 归一化并逐条回写。逐条更新与本表 insert 按条进行的风格一致；样例量级下足够，
        // 真实大批量榜单出现性能问题时再改成 CASE 批量更新（先简单、有问题再优化）。
        for (ReferenceVideoScoringRow row : rows) {
            BigDecimal qualityScore = normalize(rawScores.get(row.getVideoId()), minRaw, maxRaw);
            knowledgeReferenceVideoMapper.updateQualityScore(row.getVideoId(), qualityScore);
        }
    }

    /**
     * §2.7 第 1–3 步：算出单个视频归一化前的原始分 rawScore = engagementLog * sentimentMul。
     * view 缺失或为 0 → 返回 null（不打分）：没有播放量做分母，互动率无从谈起。
     */
    private Double computeRawScore(ReferenceVideoScoringRow row) {
        Long view = row.getViewCount();
        if (view == null || view <= 0) {
            return null;
        }
        double viewValue = view.doubleValue();

        // 第 1 步：用「率」而非绝对量，避免大播放量碾压；缺失的单项互动按 0 处理（无该互动）
        double coinRate = nullToZero(row.getCoinCount()) / viewValue;
        double favoriteRate = nullToZero(row.getFavoriteCount()) / viewValue;
        double likeRate = nullToZero(row.getLikeCount()) / viewValue;
        double replyRate = nullToZero(row.getReplyCount()) / viewValue;
        double danmakuRate = nullToZero(row.getDanmakuCount()) / viewValue;

        // 第 2 步：加权互动分 + 对数压制长尾爆款
        double engagement = knowledgeQualityProperties.getCoinWeight() * coinRate
                + knowledgeQualityProperties.getFavoriteWeight() * favoriteRate
                + knowledgeQualityProperties.getLikeWeight() * likeRate
                + knowledgeQualityProperties.getReplyWeight() * replyRate
                + knowledgeQualityProperties.getDanmakuWeight() * danmakuRate;
        double engagementLog = Math.log(1 + knowledgeQualityProperties.getLogScale() * engagement);

        // 第 3 步：情绪因子（含小样本置信度衰减）。无情绪样本时 factor=0.5 → 乘子 1.0（中性，不奖不罚）
        long sentimentSampleCount = row.getPosCount() + row.getNegCount();
        double rawSentiment = (sentimentSampleCount == 0)
                ? 0.5
                : (double) row.getPosCount() / sentimentSampleCount;
        double confidence = sentimentSampleCount
                / (sentimentSampleCount + knowledgeQualityProperties.getSentimentSmoothingK());
        double sentimentFactor = 0.5 + (rawSentiment - 0.5) * confidence;
        double sentimentMul = knowledgeQualityProperties.getSentimentMulBase()
                + knowledgeQualityProperties.getSentimentMulSpan() * sentimentFactor;

        return engagementLog * sentimentMul;
    }

    /**
     * §2.7 第 4 步：把原始分按分区 min-max 归一化到 0–100，保留两位小数（对齐 DECIMAL(6,2)）。
     * rawScore 为 null（不打分）→ 写回 NULL；分区内只有一条或全相等（max==min）→ 中性兜底分。
     */
    private BigDecimal normalize(Double rawScore, Double minRaw, Double maxRaw) {
        if (rawScore == null) {
            return null;
        }
        double value;
        if (minRaw == null || maxRaw == null || maxRaw.doubleValue() == minRaw.doubleValue()) {
            value = knowledgeQualityProperties.getFallbackScore();
        } else {
            value = 100.0 * (rawScore - minRaw) / (maxRaw - minRaw);
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private double nullToZero(Long value) {
        return value == null ? 0.0 : value.doubleValue();
    }
}
