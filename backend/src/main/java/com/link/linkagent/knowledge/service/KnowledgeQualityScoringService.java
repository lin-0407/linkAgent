package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeQualityProperties;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoQualityRecomputeResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoScoringRow;
import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 视频案例质量打分服务（阶段 5.1c）。
 * <p>
 * 在知识库架构中的定位：知识引用层（Knowledge Layer）的离线质量计算组件，
 * 负责根据视频互动数据和弹幕情绪样本，为每条知识引用视频产出一个 0-100 的相对质量分。
 * <p>
 * 核心设计：实现 §2.7 的质量公式 v1 —— 互动率 → 加权对数互动分 → 情绪因子乘子 → 按分区 min-max 归一化到 0-100。
 * 之所以「按分区」归一化：不同分区的互动率基线差异极大（知识区的投币率 vs 娱乐区的播放量级不可直接比），
 * 分区内相对排名才有业务意义——一个在鬼畜区排名前 10% 的视频对知识区创作可能毫无参考价值。
 * <p>
 * 技术决策：纯 SQL + 数学计算，不依赖 Milvus 向量检索 / Embedding 模型。
 * 打分时机是「导入后按受影响分区重算」，因为归一化是分区内相对值——新增一条样例会改变该分区的 min/max，
 * 必须连带刷新同分区其它视频的分数，否则增量视频和存量视频的分数基准不统一。
 * <p>
 * 小样本防护策略：低样本分区（样本数 < minReliableSampleSize 或 min == max）只回写 raw_quality_score，
 * 不回写 quality_score（0-100 分）。这样排序时还能用 raw_quality_score 做兜底相对排序，
 * 但不会把 3 条样本算出的 60 / 0 / 100 这种跳跃分布展示为确定的质量结论，避免误导创作者。
 */
@Service
public class KnowledgeQualityScoringService {

    /** 知识引用视频表的 Mapper，负责批量拉取打分原始数据和逐条回写质量分。 */
    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;

    /** 质量公式的运行期配置（权重、对数缩放系数、情绪平滑参数等），支持通过配置文件热调参。 */
    private final KnowledgeQualityProperties knowledgeQualityProperties;

    public KnowledgeQualityScoringService(KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                          KnowledgeQualityProperties knowledgeQualityProperties) {
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
        this.knowledgeQualityProperties = knowledgeQualityProperties;
    }

    /**
     * 重算一批指定分区的质量分（导入后的增量重算入口）。
     * <p>
     * 设计意图：视频导入后只重算受影响的几个分区，而非全表，减少不必要的计算开销。
     * 加 {@link Transactional} 的原因：单独调用时让「一个分区的全部回写」原子化——要么整个分区都算完，
     * 要么都不更新，避免部分视频更新、部分未更新的中间态；被导入流程（已在事务中）调用时按 REQUIRED 传播
     * 合并进同一事务，从而能读到刚插入、尚未提交的新视频，保证归一化时新老视频在同一基准下计算。
     * <p>
     * 空白 / null 分区直接跳过——这些视频的主分区字段为空，按设计不参与分区相对打分，
     * 因为它们无法归入任何一个可比较的同类组。
     *
     * @param categories 需要重算的分区名集合，可能包含重复值或空值
     */
    @Transactional
    public void recomputeCategories(Collection<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return;
        }
        // 用 LinkedHashSet 去重同时保持插入顺序；去重避免同一分区被重复重算（浪费 SQL + CPU）
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
     * 重算所有已有分区的质量分（全量刷新入口）。
     * <p>
     * 主要用于以下场景：质量公式升级（如新增弹幕权重、调整对数缩放系数）、
     * 表字段扩展（如增加新的互动指标列）后统一刷新全部历史数据的质量分。
     * 复用单个分区重算逻辑，避免在「全量重算」和「增量重算」之间维护两套打分公式，
     * 防止公式调整时只改一处、另一处遗留旧公式导致数据不一致。
     *
     * @return 重算结果摘要，包含受影响的分类数量和重算时间戳
     */
    @Transactional
    public ReferenceVideoQualityRecomputeResponse recomputeAllCategories() {
        List<String> categories = knowledgeReferenceVideoMapper.listScoringCategories();
        recomputeCategories(categories);
        return new ReferenceVideoQualityRecomputeResponse(categories.size(), LocalDateTime.now());
    }

    /**
     * 重算单个分区的所有视频质量分（核心算法入口）。
     * <p>
     * 算法分两步（两步之间有数据依赖——归一化需要全分区的 min/max，必须先算完全部原始分）：
     * <ol>
     *   <li><b>计算原始分</b>：逐个视频算 {@link #computeRawScore}，同时收集可打分视频的 min/max 原始分。
     *       原始分独立于同分区其它视频，适合低样本时直接作为兜底排序信号。</li>
     *   <li><b>归一化 + 回写</b>：样本充足时做 min-max 归一化到 0-100；样本不足时只写原始分、不写归一化分。
     *       逐条回写而非批量 CASE WHEN，原因：当前视频量级下单条 UPDATE 完全够用，
     *       等真实出现万级以上批量重算性能瓶颈时再改为批量更新（先简单、有问题再优化）。</li>
     * </ol>
     * <p>
     * 边界条件：只对「可打分」（view 有效且大于 0）的视频参与 min/max 计算；
     * view 缺失或为 0 的视频原始分返回 null，归一化后也写回 NULL——没有播放量做分母，互动率无从谈起。
     *
     * @param category 分区名（如"知识"、"游戏"）
     */
    private void recomputeCategory(String category) {
        List<ReferenceVideoScoringRow> rows =
                knowledgeReferenceVideoMapper.listScoringRowsByCategory(category);
        if (rows.isEmpty()) {
            return;
        }

        // 先算原始分（可能为 null），同时收集可打分视频的 min/max；原始分独立于其它视频，适合低样本兜底排序。
        Map<String, Double> rawScores = new LinkedHashMap<>();
        Double minRaw = null;
        Double maxRaw = null;
        int validSampleCount = 0;
        for (ReferenceVideoScoringRow row : rows) {
            Double rawScore = computeRawScore(row);
            rawScores.put(row.getVideoId(), rawScore);
            if (rawScore != null) {
                validSampleCount++;
                // 首次赋值时直接用首个有效值初始化 min/max，避免与 0 混淆（原始分可为负数）
                minRaw = (minRaw == null) ? rawScore : Math.min(minRaw, rawScore);
                maxRaw = (maxRaw == null) ? rawScore : Math.max(maxRaw, rawScore);
            }
        }

        // 第 2 步：在遍历前统一判断可靠性，避免每条视频都重新评估（分区内结果一致，只需算一次）
        boolean reliableDistribution = isReliableDistribution(validSampleCount, minRaw, maxRaw);

        // 归一化并逐条回写。逐条更新与本表 insert 按条进行的风格一致；样例量级下足够，
        // 真实大批量榜单出现性能问题时再改成 CASE 批量更新（先简单、有问题再优化）。
        for (ReferenceVideoScoringRow row : rows) {
            Double rawScore = rawScores.get(row.getVideoId());
            BigDecimal rawQualityScore = toRawQualityScore(rawScore);
            // 分布不可靠时 qualityScore 写 NULL，通过 qualityScoreReliable=false 标记让展示层区分"未打分"和"0 分"
            BigDecimal qualityScore = reliableDistribution ? normalize(rawScore, minRaw, maxRaw) : null;
            boolean qualityScoreReliable = qualityScore != null;
            knowledgeReferenceVideoMapper.updateQualityScores(
                    row.getVideoId(), rawQualityScore, qualityScore, validSampleCount, qualityScoreReliable);
        }
    }

    /**
     * §2.7 第 1–3 步：算出单个视频归一化前的原始分。
     * <p>
     * 公式：rawScore = engagementLog * sentimentMul
     * <ol>
     *   <li><b>互动率计算</b>：各互动维度（投币/收藏/点赞/回复/弹幕）除以播放量得到「率」而非绝对量。
     *       用率而非量的原因：避免大播放量视频碾压小播放量精品——一个 100 万播放的平庸视频互动绝对量
     *       远高于一个 1 万播放但互动率极高的优质视频，但后者对创作者的参考价值可能更大。</li>
     *   <li><b>加权对数压制</b>：加权求和后用 ln(1 + scale * engagement) 取对数。
     *       对数变换的作用：压制极少数爆款视频的超高互动率对分布的影响——如果不取对数，
     *       一个互动率 50% 的爆款会让分区 min/max 跨度极大，导致其它正常视频归一化后都挤在低分段。</li>
     *   <li><b>情绪因子</b>：基于弹幕正负情感样本计算，含小样本置信度衰减（贝叶斯平滑思想）。
     *       无情绪样本时 rawSentiment=0.5、confidence=0，factor=0.5，乘子退化为 1.0（中性，不奖不罚）；
     *       样本越多，confidence 越接近 1.0，真实情绪占比越主导 factor。</li>
     * </ol>
     * <p>
     * view 缺失或为 0 时直接返回 null：没有播放量做分母，互动率公式无意义。
     *
     * @param row 单条视频的互动数据 + 情绪样本行
     * @return 原始质量分（未归一化），无有效播放量时返回 null
     */
    private Double computeRawScore(ReferenceVideoScoringRow row) {
        Long view = row.getViewCount();
        if (view == null || view <= 0) {
            return null;
        }
        double viewValue = view.doubleValue();

        // 第 1 步：互动率 = 各项互动 / 播放量。缺失的单项互动按 0 处理（该视频无此类互动）
        double coinRate = nullToZero(row.getCoinCount()) / viewValue;
        double favoriteRate = nullToZero(row.getFavoriteCount()) / viewValue;
        double likeRate = nullToZero(row.getLikeCount()) / viewValue;
        double replyRate = nullToZero(row.getReplyCount()) / viewValue;
        double danmakuRate = nullToZero(row.getDanmakuCount()) / viewValue;

        // 第 2 步：加权互动分 = 各维度率 * 权重求和，再取对数。
        // 权重由 KnowledgeQualityProperties 配置，支持按不同分区或创作者类型调参。
        double engagement = knowledgeQualityProperties.getCoinWeight() * coinRate
                + knowledgeQualityProperties.getFavoriteWeight() * favoriteRate
                + knowledgeQualityProperties.getLikeWeight() * likeRate
                + knowledgeQualityProperties.getReplyWeight() * replyRate
                + knowledgeQualityProperties.getDanmakuWeight() * danmakuRate;
        // ln(1 + scale * x)：x→0 时 ≈ scale * x（线性），x 很大时 ≈ ln(scale * x)（对数压制）
        double engagementLog = Math.log(1 + knowledgeQualityProperties.getLogScale() * engagement);

        // 第 3 步：情绪因子——基于弹幕正负情感比例 + 贝叶斯平滑（小样本向 0.5 回归）。
        // 设计权衡：只用弹幕做情绪是因为弹幕是 B 站特有的"即时反馈"——观众看到共鸣点会立即发弹幕，
        // 相比评论区更有时间密度和情感浓度。后续可扩展加入评论情感分析。
        long sentimentSampleCount = row.getPosCount() + row.getNegCount();
        double rawSentiment = (sentimentSampleCount == 0)
                ? 0.5  // 无样本时默认 0.5（中性），等价于"无法判断情绪倾向"
                : (double) row.getPosCount() / sentimentSampleCount;
        // 贝叶斯平滑：加 K 条虚拟样本（均为 0.5），使小样本向 0.5 回归，避免 1 条正向样本 = 100% 好评
        double confidence = sentimentSampleCount
                / (sentimentSampleCount + knowledgeQualityProperties.getSentimentSmoothingK());
        double sentimentFactor = 0.5 + (rawSentiment - 0.5) * confidence;
        // 线性映射到 [sentimentMulBase, sentimentMulBase + sentimentMulSpan] 区间，
        // factor=0.5 时乘子=1.0（中性），factor 越高乘子越大（正向情绪奖励）
        double sentimentMul = knowledgeQualityProperties.getSentimentMulBase()
                + knowledgeQualityProperties.getSentimentMulSpan() * sentimentFactor;

        return engagementLog * sentimentMul;
    }

    /**
     * 判断当前分区样本量和分布是否足够可靠，以决定是否产出 0-100 归一化分。
     * <p>
     * 三个条件缺一不可：
     * <ol>
     *   <li>可打分样本数 >= minReliableSampleSize（至少 2 条，防止单样本归一化无意义）</li>
     *   <li>min/max 都存在（即至少有一条可打分视频）</li>
     *   <li>max > min（分区内质量有差异，归一化才有区分度。
     *       若全部视频原始分相等，min-max 归一化将全部产出 0 分，毫无意义）</li>
     * </ol>
     * <p>
     * 不满足时保留 raw_quality_score 作为兜底排序信号——展示层可以用它做跨分区相对排序，
     * 但不会把 3 条样本算出的 {60, 0, 100} 当作确定的质量结论展示给创作者。
     *
     * @param validSampleCount 分区内可打分视频数量（view 有效且 > 0）
     * @param minRaw 分区内最小原始分（可能为 null 表示无有效视频）
     * @param maxRaw 分区内最大原始分（可能为 null 表示无有效视频）
     * @return true 表示可以产出归一化 0-100 分
     */
    private boolean isReliableDistribution(int validSampleCount, Double minRaw, Double maxRaw) {
        // 至少 2 条样本才有"分布"的概念；配置允许调高阈值（如 10）以要求更稳定的统计
        int minReliableSampleSize = Math.max(2, knowledgeQualityProperties.getMinReliableSampleSize());
        return validSampleCount >= minReliableSampleSize
                && minRaw != null
                && maxRaw != null
                && maxRaw.doubleValue() > minRaw.doubleValue();
    }

    /**
     * 将原始分 double 转为数据库 DECIMAL，保留 6 位小数。
     * <p>
     * 保留 6 位而非 2 位的原因：原始分不对外展示（展示层用 0-100 归一化分），
     * 保留更高精度便于排查公式调整效果和小样本时兜底排序区分微小的质量差异。
     * 展示层不应直接把它当作 0-100 分使用，应优先使用 quality_score 字段。
     *
     * @param rawScore 原始分 double 值，可能为 null
     * @return 6 位精度的 BigDecimal，null 入参返回 null
     */
    private BigDecimal toRawQualityScore(Double rawScore) {
        if (rawScore == null) {
            return null;
        }
        return BigDecimal.valueOf(rawScore).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * §2.7 第 4 步：min-max 归一化。
     * <p>
     * 公式：normalized = 100 * (rawScore - minRaw) / (maxRaw - minRaw)
     * <p>
     * 产出 0-100 分，保留两位小数（对齐数据库 DECIMAL(6,2) 列定义）。
     * 边界行为：rawScore（即该视频原始分）为 null 时直接返回 null——这是"view 无效、不可打分"的语义，
     * 与"样本不足导致整分区不归一化"是两个独立的条件，由调用方分别处理。
     * <p>
     * 注意：调用方保证 maxRaw > minRaw（已通过 isReliableDistribution 校验），
     * 此处不做除零保护，以 fail-fast 暴露上游校验遗漏。
     *
     * @param rawScore 单视频原始分
     * @param minRaw 分区最小原始分
     * @param maxRaw 分区最大原始分（必须 > minRaw）
     * @return 0-100 两位小数 BigDecimal；rawScore 为 null 时返回 null
     */
    private BigDecimal normalize(Double rawScore, Double minRaw, Double maxRaw) {
        if (rawScore == null) {
            return null;
        }
        double value = 100.0 * (rawScore - minRaw) / (maxRaw - minRaw);
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 安全地将 Long 转为 double，null 视为 0。
     * <p>
     * 设计意图：数据库中互动数字段可能为 NULL（数据采集不完整或爬虫未抓取到），
     * 在计算互动率时，NULL 等价于"该视频没有此类互动"，按 0 处理是最合理的语义。
     *
     * @param value 互动数字段值，可能为 null
     * @return 非 null 则转 double，null 返回 0.0
     */
    private double nullToZero(Long value) {
        return value == null ? 0.0 : value.doubleValue();
    }
}
