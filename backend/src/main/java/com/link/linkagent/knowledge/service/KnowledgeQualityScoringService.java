package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeQualityProperties;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoImportRequest.VideoStats;
import com.link.linkagent.knowledge.model.ReferenceVideoQualityRecomputeResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoScoringRow;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 脚本指数保留固定刻度，同分区互动率只作有限修正，避免把入选案例强行拉成最低零分、最高满分。
 * 沿用质量分字段以兼容已有接口；情绪关键词和分区样本数均不再决定能否展示分数。
 */
@Service
public class KnowledgeQualityScoringService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeQualityScoringService.class);
    private final KnowledgeReferenceVideoMapper mapper;
    private final KnowledgeQualityProperties properties;

    public KnowledgeQualityScoringService(KnowledgeReferenceVideoMapper mapper,
                                          KnowledgeQualityProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /** 旧案例必须在使用新页面前统一换算，启动时只读写本地统计，不调用采集脚本或模型。 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recomputeOnStartup() {
        log.info("案例库分区互动分重算完成：{}", recomputeAllCategories());
    }

    /** 整个分区在同一事务内更新，避免导入后新旧案例使用不同的互动率基准。 */
    @Transactional
    public void recomputeCategories(Collection<String> categories) {
        if (categories == null || categories.isEmpty()) return;
        Set<String> distinct = new LinkedHashSet<>();
        for (String category : categories) distinct.add(TextUtil.trimToNull(category));
        for (String category : distinct) recomputeCategory(category);
    }

    @Transactional
    public ReferenceVideoQualityRecomputeResponse recomputeAllCategories() {
        List<String> categories = mapper.listScoringCategories();
        recomputeCategories(categories);
        return new ReferenceVideoQualityRecomputeResponse(categories.size(), LocalDateTime.now());
    }

    private void recomputeCategory(String category) {
        List<ReferenceVideoScoringRow> rows = mapper.listScoringRowsByCategory(category);
        if (rows.isEmpty()) return;
        Map<String, Double> baseScores = new LinkedHashMap<>();
        Map<String, double[]> ratesByVideo = new LinkedHashMap<>();
        List<double[]> rateSamples = new ArrayList<>();
        int validCount = 0;
        for (ReferenceVideoScoringRow row : rows) {
            // 复用采集脚本的 Java 同源公式，旧记录即使尚未回填指数也能由完整统计得到相同基础分。
            Double base = ReferenceVideoCollectionPolicy.score(new VideoStats(row.getViewCount(), row.getLikeCount(),
                    row.getCoinCount(), row.getFavoriteCount(), row.getDanmakuCount(), row.getReplyCount()));
            baseScores.put(row.getVideoId(), base);
            if (base == null) continue;
            validCount++;
            // 真实零播放仍有零分基础指数，但没有可定义的互动率，不混入分区中位数。
            if (row.getViewCount() == 0) continue;
            double views = row.getViewCount().doubleValue();
            double[] rates = {row.getCoinCount() / views, row.getFavoriteCount() / views,
                    row.getLikeCount() / views, row.getReplyCount() / views, row.getDanmakuCount() / views};
            ratesByVideo.put(row.getVideoId(), rates);
            rateSamples.add(rates);
        }

        double[] medians = medians(rateSamples);
        double[] weights = {properties.getCoinWeight(), properties.getFavoriteWeight(), properties.getLikeWeight(),
                properties.getReplyWeight(), properties.getDanmakuWeight()};
        for (ReferenceVideoScoringRow row : rows) {
            Double base = baseScores.get(row.getVideoId());
            BigDecimal score = null;
            if (base != null) {
                double correction = 0;
                double[] rates = ratesByVideo.get(row.getVideoId());
                // 未标注分区时仅展示基础分，不把这些视频当成一个可互相比对的赛道。
                if (category != null && rates != null) {
                    for (int metric = 0; metric < weights.length; metric++) {
                        double denominator = rates[metric] + medians[metric];
                        if (denominator > 0) {
                            correction += weights[metric] * (rates[metric] - medians[metric]) / denominator;
                        }
                    }
                }
                double value = base + properties.getMaxCategoryAdjustment() * correction;
                score = BigDecimal.valueOf(Math.max(0, Math.min(100, value))).setScale(2, RoundingMode.HALF_UP);
            }
            // 兼容字段仅表示统计完整、可展示，不再承载小样本置信度；缺失统计始终保留为空。
            mapper.updateQualityScores(row.getVideoId(), ReferenceVideoCollectionPolicy.storedScore(base), score,
                    category == null ? 0 : validCount, score != null);
        }
    }

    private double[] medians(List<double[]> samples) {
        double[] result = new double[5];
        if (samples.isEmpty()) return result;
        for (int metric = 0; metric < result.length; metric++) {
            double[] values = new double[samples.size()];
            for (int sample = 0; sample < samples.size(); sample++) values[sample] = samples.get(sample)[metric];
            Arrays.sort(values);
            int middle = values.length / 2;
            result[metric] = values.length % 2 == 1 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
        }
        return result;
    }
}
