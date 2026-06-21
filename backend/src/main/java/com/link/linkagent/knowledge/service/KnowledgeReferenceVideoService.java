package com.link.linkagent.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
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

/**
 * 跨分区视频案例库存储服务（阶段 5.1a）。
 * 本阶段只做「导入落库 + 分页列表」，完全不依赖 Milvus 与 Embedding：
 * 评论 / 弹幕清洗（5.1b）、质量打分与向量索引（5.1c）都还没接入，因此在 RAG 关闭、甚至没有向量库环境下也能独立跑通，
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
            "seed"
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

    public KnowledgeReferenceVideoService(KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                          KnowledgeReferenceCleaningService knowledgeReferenceCleaningService,
                                          KnowledgeReferenceChunkService knowledgeReferenceChunkService,
                                          KnowledgeQualityScoringService knowledgeQualityScoringService,
                                          ObjectMapper objectMapper) {
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
        this.knowledgeReferenceCleaningService = knowledgeReferenceCleaningService;
        this.knowledgeReferenceChunkService = knowledgeReferenceChunkService;
        this.knowledgeQualityScoringService = knowledgeQualityScoringService;
        this.objectMapper = objectMapper;
    }

    /**
     * 导入一批视频案例到父表。
     * 整批共用一个 source/tier：tier 优先取请求显式值（如 manual_bv 指定 COMPETITOR），否则由 source 推导。
     * 加事务是为了让「一批要么全进、要么全不进」，避免中途异常留下半批脏数据。
     */
    @Transactional
    public ReferenceVideoImportResponse importReferenceVideos(ReferenceVideoImportRequest request) {
        String source = request.source().trim();
        if (!ALLOWED_SOURCES.contains(source)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的数据来源: " + source);
        }
        String tier = resolveTier(request.tier());
        String defaultCategory = TextUtil.trimToNull(request.category());

        int imported = 0;
        int skipped = 0;
        // 本批已处理的 BV，配合库内查询实现重复导入幂等：同一 BV 不重复入库
        Set<String> seenBvIds = new HashSet<>();
        // 本批实际入库视频涉及的分区（非空），导入后据此重算这些分区的归一化质量分
        Set<String> affectedCategories = new LinkedHashSet<>();
        for (ReferenceVideoImportRequest.VideoItem video : request.videos()) {
            String bvId = TextUtil.trimToNull(video.bvId());
            // 仅当视频带 BV 时去重：库里已有或本批已出现过相同 BV 就跳过；seed / 手动无 BV 的条目不参与去重
            if (bvId != null
                    && (seenBvIds.contains(bvId) || knowledgeReferenceVideoMapper.countByBvId(bvId) > 0)) {
                skipped++;
                continue;
            }

            ReferenceVideoRecord record = new ReferenceVideoRecord();
            // video_id 用 UUID，作为跨任务稳定标识，同时是子表外键与未来向量文档 ID 的来源
            record.setVideoId(UUID.randomUUID().toString());
            record.setBvId(bvId);
            record.setTier(tier);
            record.setCategory(resolveCategory(video.category(), defaultCategory));
            record.setTitle(video.title().trim());
            record.setDescription(TextUtil.trimToNull(video.description()));
            record.setTags(toTagsJson(video.tags()));
            applyStats(record, video.stats());
            record.setSource(source);
            record.setPublishTimeText(TextUtil.trimToNull(video.publishTimeText()));

            // 5.1b：清洗评论 / 弹幕，得到优质子条目与亮点摘要。摘要写回父表，子条目随后落子表。
            // 清洗含一次摘要 LLM 调用，目前放在事务内（导入样例量小可接受）；后续接离线脚本大批量时，可改为先清洗后落库、拆分事务。
            KnowledgeReferenceCleaningService.CleaningResult cleaning =
                    knowledgeReferenceCleaningService.clean(
                            record.getVideoId(), record.getTitle(), video.comments(), video.danmaku());
            record.setHighlightSummary(cleaning.highlightSummary());

            knowledgeReferenceVideoMapper.insertReferenceVideo(record);
            for (ReferenceVideoItemRecord item : cleaning.items()) {
                knowledgeReferenceVideoMapper.insertReferenceVideoItem(item);
            }
            // 同步生成主题中块，形成「父视频 / 主题中块 / 原始证据小块」三层结构。
            // 中块只整理已入库材料，不额外调用 LLM，避免导入链路因为主题摘要而引入额外成本和编造风险。
            for (ReferenceVideoChunkRecord chunk : knowledgeReferenceChunkService.buildChunks(record, cleaning.items())) {
                knowledgeReferenceVideoMapper.insertReferenceVideoChunk(chunk);
            }
            imported++;
            if (bvId != null) {
                seenBvIds.add(bvId);
            }
            // 分区缺失的视频按设计不参与分区相对打分（quality_score 保持 NULL），故只收非空分区
            if (record.getCategory() != null) {
                affectedCategories.add(record.getCategory());
            }
        }
        // 5.1c 质量打分：新案例会改变所在分区的 min/max，需重算受影响分区全部视频的归一化分。
        // 与导入同一事务：此处重算的 SELECT 能读到上面刚插入的行，算完回写后整体原子提交。
        knowledgeQualityScoringService.recomputeCategories(affectedCategories);
        return new ReferenceVideoImportResponse(request.videos().size(), imported, skipped, source, tier);
    }

    /**
     * 分页查询案例列表，支持分区 / 层级可选过滤。
     * page、size 在这里做兜底纠偏（最小 1、size 上限 100），即使控制器层校验被绕过也不会产生非法 OFFSET。
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
     * 当前榜单 / seed / 手动导入默认都视为「值得参照的优品」(BENCHMARK)，COMPETITOR / OWN_HISTORY 需调用方显式声明。
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
        return new ReferenceVideoResponse(
                record.getId(),
                record.getVideoId(),
                record.getBvId(),
                record.getTier(),
                record.getCategory(),
                record.getTitle(),
                record.getDescription(),
                record.getTags(),
                record.getViewCount(),
                record.getLikeCount(),
                record.getCoinCount(),
                record.getFavoriteCount(),
                record.getDanmakuCount(),
                record.getReplyCount(),
                record.getHighlightSummary(),
                record.getRawQualityScore(),
                record.getQualityScore(),
                record.getQualitySampleCount(),
                record.isQualityScoreReliable(),
                record.getSource(),
                record.getPublishTimeText(),
                record.getEmbeddingStatus(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
