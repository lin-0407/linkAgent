package com.link.linkagent.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.BilibiliCoverUrlPolicy;
import com.link.linkagent.knowledge.model.ReferenceVideoImportRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoImportResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoChunkRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoItemRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoPageResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoResponse;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;

/**
 * 跨分区视频案例库存储服务 — Pipeline 中「导入落库 + 分页列表」的核心枢纽。
 * <p>
 * <b>Pipeline 角色</b>：
 * 这是案例库导入链路的<b>数据中心节点</b>——所有导入路径（前端 BV 一键采集 / 脚本批量导入 / 种子数据）
 * 最终都汇聚到这个服务完成落库。它编排了清洗、中块生成、质量打分三个子步骤，形成完整的导入事务：
 * <pre>
 * 原始数据 → 清洗（CleaningService）→ 写父表 → 写子表 → 生成中块（ChunkService）→ 写中块表 → 质量重算（ScoringService）
 * </pre>
 * <p>
 * 本阶段完全不依赖 Milvus 与 Embedding——向量索引由独立的 IndexService 负责，
 * 因此即使 RAG 关闭、向量库不可用，本服务的导入和列表功能也能独立跑通，
 * 这正是阶段 5「基础设施可关、关掉时优雅降级」要求的存储底座。
 */
@Service
public class KnowledgeReferenceVideoService {

    /** 允许的数据来源，对应离线脚本契约里的 source 取值；非白名单来源直接拒绝，避免脏来源污染案例库。 */
    private static final Set<String> ALLOWED_SOURCES = Set.of(
            "bilibili_rank_daily",
            "bilibili_rank_weekly",
            "bilibili_rank_monthly",
            "manual_bv",
            "seed",
            "bilibili_auto"
    );

    /** 允许的案例层级，与表 A tier 字段语义一致。 */
    private static final Set<String> ALLOWED_TIERS = Set.of(
            "BENCHMARK",
            "COMPETITOR",
            "OWN_HISTORY"
    );

    /** 列表单页上限，防止恶意传入超大 size 拖垮查询。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;
    private final KnowledgeReferenceCleaningService knowledgeReferenceCleaningService;
    private final KnowledgeReferenceChunkService knowledgeReferenceChunkService;
    private final KnowledgeQualityScoringService knowledgeQualityScoringService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeReferenceVideoService(KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                          KnowledgeReferenceCleaningService knowledgeReferenceCleaningService,
                                          KnowledgeReferenceChunkService knowledgeReferenceChunkService,
                                          KnowledgeQualityScoringService knowledgeQualityScoringService,
                                          ObjectMapper objectMapper, TransactionTemplate transactionTemplate) {
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
        this.knowledgeReferenceCleaningService = knowledgeReferenceCleaningService;
        this.knowledgeReferenceChunkService = knowledgeReferenceChunkService;
        this.knowledgeQualityScoringService = knowledgeQualityScoringService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /** 清洗和摘要在事务外准备，整批事实写入仍原子提交；唯一键处理并发采集同一 BV。 */
    public ReferenceVideoImportResponse importReferenceVideos(ReferenceVideoImportRequest request) {
        String source = request.source().trim();
        if (!ALLOWED_SOURCES.contains(source)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的数据来源: " + source);
        String tier = resolveTier(request.tier());
        boolean automatic = ReferenceVideoCollectionPolicy.AUTO_SOURCE.equals(source);
        if (automatic && !"BENCHMARK".equals(tier)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "自动采集只新增标杆案例");
        double threshold = request.minVideoIndex() == null ? 60 : request.minVideoIndex();
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指数阈值必须在0到100之间");
        // 先校验整批，避免第二条输入不合格时已经付出第一条的摘要成本。
        for (var video : request.videos()) {
            if (automatic) ReferenceVideoCollectionPolicy.validateAutomatic(video, threshold, LocalDateTime.now(ReferenceVideoCollectionPolicy.ZONE));
            ReferenceVideoCollectionPolicy.publishedAt(video);
        }
        List<PreparedVideo> prepared = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int repeated = 0;
        for (var video : request.videos()) {
            String bvId = TextUtil.trimToNull(video.bvId());
            if (bvId != null && !seen.add(bvId)) { repeated++; continue; }
            var record = new ReferenceVideoRecord();
            record.setVideoId(UUID.randomUUID().toString());
            record.setBvId(bvId);
            record.setCoverUrl(BilibiliCoverUrlPolicy.normalize(video.coverUrl()));
            record.setTier(tier);
            record.setCategory(resolveCategory(video.category(), TextUtil.trimToNull(request.category())));
            record.setTitle(video.title().trim());
            record.setDescription(TextUtil.trimToNull(video.description()));
            record.setTags(toTagsJson(video.tags()));
            applyStats(record, video.stats());
            record.setSource(source);
            record.setPublishTimeText(TextUtil.trimToNull(video.publishTimeText()));
            record.setPublishedAt(ReferenceVideoCollectionPolicy.publishedAt(video));
            record.setStatsFetchedAt(ReferenceVideoCollectionPolicy.epochTime(video.statsFetchedAtEpochSeconds()));
            record.setVideoIndexScore(ReferenceVideoCollectionPolicy.storedScore(ReferenceVideoCollectionPolicy.score(video.stats())));
            record.setVideoIndexVersion(record.getVideoIndexScore() == null ? null : ReferenceVideoCollectionPolicy.VERSION);
            record.setCollectionMinIndex(automatic ? BigDecimal.valueOf(threshold) : null);
            boolean exists = bvId != null && knowledgeReferenceVideoMapper.countByBvId(bvId) > 0;
            if (exists) {
                prepared.add(new PreparedVideo(video, record, List.of(), List.of(), true));
                continue;
            }
            var cleaning = knowledgeReferenceCleaningService.clean(record.getVideoId(), record.getTitle(), video.comments(), video.danmaku());
            record.setHighlightSummary(cleaning.highlightSummary());
            prepared.add(new PreparedVideo(video, record, cleaning.items(), knowledgeReferenceChunkService.buildChunks(record, cleaning.items()), false));
        }
        int initialSkipped = repeated;
        try {
            return transactionTemplate.execute(status -> persistPrepared(request, prepared, source, tier, automatic, threshold, initialSkipped));
        } catch (DuplicateKeyException concurrentImport) {
            // 并发请求可能在清洗期间抢先提交：本事务整体回滚后用同一份准备结果重查，不再调用模型。
            return transactionTemplate.execute(status -> persistPrepared(request, prepared, source, tier, automatic, threshold, initialSkipped));
        }
    }

    private ReferenceVideoImportResponse persistPrepared(ReferenceVideoImportRequest request, List<PreparedVideo> prepared,
            String source, String tier, boolean automatic, double threshold, int skipped) {
        int imported = 0;
        int updated = 0;
        Set<String> categories = new LinkedHashSet<>();
        for (var video : prepared) {
            if (automatic) ReferenceVideoCollectionPolicy.validateAutomatic(video.input(), threshold, LocalDateTime.now(ReferenceVideoCollectionPolicy.ZONE));
            var record = video.record();
            var existing = record.getBvId() == null ? java.util.Optional.<ReferenceVideoRecord>empty()
                    : knowledgeReferenceVideoMapper.findActiveByBvId(record.getBvId());
            if (existing.isPresent()) {
                if (automatic && knowledgeReferenceVideoMapper.updateCollectedMetadata(existing.get().getVideoId(), record) == 1) {
                    updated++;
                    if (existing.get().getCategory() != null) categories.add(existing.get().getCategory());
                } else skipped++;
                continue;
            }
            // 原记录在准备期间被删除时不把“只刷统计”的空正文误当成完整采集，下轮会重新取正文。
            if (video.wasExisting()) { skipped++; continue; }
            knowledgeReferenceVideoMapper.insertReferenceVideo(record);
            for (var item : video.items()) knowledgeReferenceVideoMapper.insertReferenceVideoItem(item);
            for (var chunk : video.chunks()) knowledgeReferenceVideoMapper.insertReferenceVideoChunk(chunk);
            imported++;
            if (record.getCategory() != null) categories.add(record.getCategory());
        }
        knowledgeQualityScoringService.recomputeCategories(categories);
        return new ReferenceVideoImportResponse(request.videos().size(), imported, skipped, source, tier, updated);
    }

    private record PreparedVideo(ReferenceVideoImportRequest.VideoItem input, ReferenceVideoRecord record,
            List<ReferenceVideoItemRecord> items, List<ReferenceVideoChunkRecord> chunks, boolean wasExisting) { }

    /**
     * 在短事务内替换封面和六项公开统计，并基于新统计重算当前分区质量分。
     * 脚本调用由上层在进入本方法前完成，避免 B 站网络等待占用数据库事务。
     */
    @Transactional
    public ReferenceVideoResponse updatePublicMetadata(String videoId,
                                                       String coverUrl,
                                                       ReferenceVideoImportRequest.VideoStats stats) {
        ReferenceVideoRecord current = knowledgeReferenceVideoMapper.findByVideoId(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "案例不存在"));
        String persistentCoverUrl = BilibiliCoverUrlPolicy.normalize(coverUrl);
        validatePublicMetadata(persistentCoverUrl, stats);

        int updated = knowledgeReferenceVideoMapper.updatePublicMetadata(
                videoId,
                persistentCoverUrl,
                stats.view(),
                stats.like(),
                stats.coin(),
                stats.favorite(),
                stats.danmaku(),
                stats.reply()
        );
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "案例不存在");
        }

        knowledgeReferenceVideoMapper.updateIndexStatistics(videoId,
                ReferenceVideoCollectionPolicy.storedScore(ReferenceVideoCollectionPolicy.score(stats)),
                ReferenceVideoCollectionPolicy.VERSION, LocalDateTime.now(ReferenceVideoCollectionPolicy.ZONE));
        String category = TextUtil.trimToNull(current.getCategory());
        if (category != null) {
            knowledgeQualityScoringService.recomputeCategories(Set.of(category));
        }
        return knowledgeReferenceVideoMapper.findByVideoId(videoId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "案例不存在"));
    }

    /**
     * 分页查询案例列表，支持分区 / 层级可选过滤。
     * page、size 在这里做兜底纠偏（最小 1、size 上限 100）——即使控制器层校验被绕过也不会产生非法 OFFSET 或过度查询。
     *
     * @param category 分区过滤，null 或空则不限制
     * @param tier     层级过滤，null 或空则不限制
     * @param page     页码（从 1 开始）
     * @param size     每页条数（上限 100）
     * @return 分页结果，含 items/total/page/size
     */
    public ReferenceVideoPageResponse listReferenceVideos(String category, String tier, int page, int size) {
        String categoryFilter = TextUtil.trimToNull(category);
        String tierFilter = normalizeTierFilter(tier);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safeSize;

        List<ReferenceVideoRecord> records =
                knowledgeReferenceVideoMapper.listReferenceVideos(categoryFilter, tierFilter, safeSize, offset);
        long total = knowledgeReferenceVideoMapper.countReferenceVideos(categoryFilter, tierFilter);
        List<ReferenceVideoResponse> items = records.stream().map(this::toResponse).toList();
        return new ReferenceVideoPageResponse(items, total, safePage, safeSize);
    }

    /**
     * 解析最终层级：显式 tier 优先（统一大写后校验白名单），否则默认 BENCHMARK。
     * <p>
     * 榜单/seed/手动导入默认都视为「值得参照的优品」（BENCHMARK），
     * COMPETITOR / OWN_HISTORY 需调用方显式声明——这防止采集脚本或 API 无意中把别人的视频标成「自己历史」。
     */
    private String resolveTier(String requestTier) {
        String provided = TextUtil.trimToNull(requestTier);
        if (provided == null) {
            return "BENCHMARK";
        }
        String normalized = provided.toUpperCase();
        if (!ALLOWED_TIERS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的案例层级: " + requestTier);
        }
        return normalized;
    }

    /**
     * 单条视频分区优先用自身的，缺失时回退到整批默认分区。
     */
    private String resolveCategory(String videoCategory, String defaultCategory) {
        String own = TextUtil.trimToNull(videoCategory);
        return own != null ? own : defaultCategory;
    }

    /**
     * 把标签数组序列化成 JSON 字符串存入 tags 列；先剔除空白标签，全空时存 NULL。
     * 序列化失败属于输入异常，按 400 返回让调用方修正，而不是吞掉错误存半截数据。
     */
    private String toTagsJson(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String tag : tags) {
            String value = TextUtil.trimToNull(tag);
            if (value != null) {
                cleaned.add(value);
            }
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(cleaned);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签序列化失败，请检查标签内容");
        }
    }

    /**
     * 把热度指标从请求拷贝到记录；stats 整体可空，缺失时各计数保持 NULL（质量打分阶段再兜底）。
     */
    private void applyStats(ReferenceVideoRecord record, ReferenceVideoImportRequest.VideoStats stats) {
        if (stats == null) {
            return;
        }
        record.setViewCount(stats.view());
        record.setLikeCount(stats.like());
        record.setCoinCount(stats.coin());
        record.setFavoriteCount(stats.favorite());
        record.setDanmakuCount(stats.danmaku());
        record.setReplyCount(stats.reply());
    }

    private void validatePublicMetadata(String coverUrl, ReferenceVideoImportRequest.VideoStats stats) {
        if (coverUrl == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "B站没有返回可持久化的封面地址");
        }
        if (stats == null
                || invalidPublicCount(stats.view())
                || invalidPublicCount(stats.like())
                || invalidPublicCount(stats.coin())
                || invalidPublicCount(stats.favorite())
                || invalidPublicCount(stats.danmaku())
                || invalidPublicCount(stats.reply())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "B站返回的公开视频统计不完整");
        }
    }

    private boolean invalidPublicCount(Long value) {
        return value == null || value < 0;
    }

    /**
     * 列表的层级过滤值做大写归一并校验，非法值直接 400，避免静默返回空列表让人误以为没数据。
     */
    private String normalizeTierFilter(String tier) {
        String value = TextUtil.trimToNull(tier);
        if (value == null) {
            return null;
        }
        String normalized = value.toUpperCase();
        if (!ALLOWED_TIERS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的案例层级过滤: " + tier);
        }
        return normalized;
    }

    private ReferenceVideoResponse toResponse(ReferenceVideoRecord record) {
        return ReferenceVideoResponse.from(record);
    }
}
