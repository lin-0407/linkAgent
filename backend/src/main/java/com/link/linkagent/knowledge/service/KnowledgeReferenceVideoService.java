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
     * 导入一批视频案例到父表，是 Pipeline 中最核心的编排方法。
     * <p>
     * <b>事务内编排流程（逐视频循环）</b>：
     * <ol>
     *   <li>白名单校验 source + 解析 tier/category</li>
     *   <li>构造父表记录 + 拷贝热度指标</li>
     *   <li><b>清洗评论/弹幕</b>（含一次 LLM 摘要调用）→ 得优质子条目 + 亮点摘要</li>
     *   <li>写父表</li>
     *   <li>逐条写子表</li>
     *   <li><b>生成主题中块</b>（纯规则、无 LLM）→ 逐中块写中块表</li>
     *   <li>同一 BV 号去重（库里已有 / 本批已出现过则跳过）</li>
     *   <li>收集受影响分区</li>
     * </ol>
     * 循环结束后，<b>质量分重算</b>：导入会改变所在分区的 min/max，需重算影响分区的全部视频归一化分。
     * <p>
     * 整批共用一个 source/tier：tier 优先取请求显式值（如 manual_bv 指定 COMPETITOR），否则默认 BENCHMARK。
     * 加 {@code @Transactional} 是为了让「一批要么全进、要么全不进」，避免中途异常留下半批脏数据。
     * 注意：清洗含一次 LLM 调用在事务内（样例量小可接受）；
     * 后续接离线脚本大批量导入时，可改为先清洗后落库、拆分事务以缩短锁持有时间。
     *
     * @param request 导入请求，含 source/videos 列表和可选的 tier/category
     * @return 导入结果，含请求总数/实际导入数/跳过数
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
            record.setCoverUrl(BilibiliCoverUrlPolicy.normalize(video.coverUrl()));
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
        return new ReferenceVideoResponse(
                record.getId(),
                record.getVideoId(),
                record.getBvId(),
                BilibiliCoverUrlPolicy.normalize(record.getCoverUrl()),
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
