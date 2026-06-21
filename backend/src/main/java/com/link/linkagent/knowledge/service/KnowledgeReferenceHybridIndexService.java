package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeHybridStore;
import com.link.linkagent.knowledge.config.KnowledgeHybridStore.HybridParentDoc;
import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexStatusResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 案例库父表 <b>原生 hybrid 索引</b> 服务（阶段 5.2d-1）：把父表案例卡片灌入自建 schema 的 hybrid 集合
 * （dense + BM25 sparse），供 5.2d-2 的原生混合检索使用。
 * <p>
 * <b>与 5.1c Spring AI 索引（{@link KnowledgeReferenceIndexService}）的有意差异</b>：
 * <ol>
 *   <li><b>整库重灌（wholesale），非增量</b>：hybrid 集合是从 MySQL（事实源）派生的副本，schema 自建。
 *       重建 = drop 旧集合 → 自建 schema 建集合 → 把非删除父卡片全量重灌。简单可复现；增量优化留后续。</li>
 *   <li><b>不回写 embedding_status</b>：那套状态属于 Spring AI 老路径（{@code creator_reference_video} 集合）；
 *       hybrid 是独立集合，不共享该状态机。</li>
 *   <li><b>手动 embedding</b>：raw v2 client 不自动嵌入，dense 向量由 {@link KnowledgeHybridStore} 内部算。</li>
 * </ol>
 * <b>双开关 + 降级</b>：需 {@code knowledge.rag.enabled} 且 {@code knowledge.rag.hybrid.enabled} 且 hybrid 库就绪，
 * 否则 400；部分批次失败不报错，写入 failedCount/warnings（同父索引范式）。
 */
@Service
public class KnowledgeReferenceHybridIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceHybridIndexService.class);

    /** 单次重灌硬上限，与接口层 @Max(1000) 对齐，二次防御一次性全库重嵌入的高成本。 */
    private static final int MAX_INDEX_HARD_LIMIT = 1000;

    /** 失败提示最多收集条数。 */
    private static final int MAX_WARNINGS = 10;

    /** 单条卡片文本上限，防超长简介撑爆单条 Embedding（与 5.1c 父索引一致）。 */
    private static final int DOC_TEXT_MAX_CHARS = 4000;

    /** 检索模式预测值：hybrid 就绪且有案例时预计走 HYBRID，否则 SQL。 */
    private static final String MODE_HYBRID = "HYBRID";
    private static final String MODE_SQL = "SQL";

    private final KnowledgeRagProperties knowledgeRagProperties;
    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;
    private final KnowledgeHybridStore knowledgeHybridStore;

    public KnowledgeReferenceHybridIndexService(KnowledgeRagProperties knowledgeRagProperties,
                                                KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                                KnowledgeHybridStore knowledgeHybridStore) {
        this.knowledgeRagProperties = knowledgeRagProperties;
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
        this.knowledgeHybridStore = knowledgeHybridStore;
    }

    /**
     * 重建（整库重灌）父表 hybrid 索引：drop 旧集合 → 自建 schema 建集合 → 从 MySQL 全量重灌父卡片。
     * RAG/hybrid 开关未启用或 hybrid 库未就绪 → 400；建集合失败 → 400（带原因）；部分批次失败写 failedCount/warnings。
     */
    public ReferenceVideoIndexResponse rebuildHybrid(ReferenceVideoIndexRequest request) {
        if (!knowledgeRagProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "知识库 RAG 业务开关未启用，请先设置 knowledge.rag.enabled=true");
        }
        if (!knowledgeRagProperties.getHybrid().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "原生 hybrid 子开关未启用，请先设置 knowledge.rag.hybrid.enabled=true（需 Milvus 服务端 ≥2.5）");
        }
        if (!knowledgeHybridStore.isReady()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "知识库 hybrid 向量库未就绪，请确认 Milvus v2 已连接且配置了 EmbeddingModel");
        }

        int maxItems = resolveMaxItems(request);
        int batchSize = Math.max(1, knowledgeRagProperties.getIndexBatchSize());

        // 重灌源：所有非删除父卡片（不限 embedding_status——hybrid 是独立集合）。复用列表查询，category/tier 传 null 即不过滤。
        List<ReferenceVideoRecord> videos = knowledgeReferenceVideoMapper.listReferenceVideos(null, null, maxItems, 0);
        if (videos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "没有可灌入 hybrid 的案例，请先导入案例");
        }

        // 先 drop 重建集合：失败则整体 400（没有集合后续插入无意义）。
        try {
            knowledgeHybridStore.recreateCollection();
        } catch (Exception exception) {
            log.error("重建 hybrid 集合失败。", exception);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "重建 hybrid 集合失败：" + normalizeError(exception));
        }

        List<String> warnings = new ArrayList<>();
        int indexed = 0;
        int failed = 0;
        for (int start = 0; start < videos.size(); start += batchSize) {
            int end = Math.min(start + batchSize, videos.size());
            List<ReferenceVideoRecord> chunk = videos.subList(start, end);
            try {
                List<HybridParentDoc> docs = chunk.stream()
                        .map(video -> new HybridParentDoc(
                                video.getVideoId(), buildDocumentText(video), video.getCategory(), video.getTier()))
                        .toList();
                try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库 hybrid 父卡片索引")) {
                    indexed += knowledgeHybridStore.insertParentDocs(docs);
                }
            } catch (Exception exception) {
                failed += chunk.size();
                if (warnings.size() < MAX_WARNINGS) {
                    warnings.add("第 " + (start + 1) + "-" + end + " 条灌入失败：" + normalizeError(exception));
                }
                log.warn("hybrid 灌入失败。range={}-{}", start + 1, end, exception);
            }
        }

        return new ReferenceVideoIndexResponse(
                true, true, videos.size(), indexed, 0, failed, warnings, LocalDateTime.now());
    }

    /**
     * hybrid 索引状态：RAG/hybrid 是否就绪 + 可重灌的父卡片总数 + 检索模式预测。
     * 注意：hybrid 是整库重灌、不在 MySQL 维护 per-card 状态，故 indexedCount/pendingCount/failedCount 恒 0，
     * totalCount 是「重灌源」的非删除父卡片数；rebuild 响应才给本次实际灌入数。
     */
    public ReferenceVideoIndexStatusResponse hybridStatus() {
        boolean ragEnabled = knowledgeRagProperties.isEnabled();
        boolean hybridReady = ragEnabled
                && knowledgeRagProperties.getHybrid().isEnabled()
                && knowledgeHybridStore.isReady();
        long total = knowledgeReferenceVideoMapper.countReferenceVideos(null, null);
        String retrievalMode = (hybridReady && total > 0) ? MODE_HYBRID : MODE_SQL;
        return new ReferenceVideoIndexStatusResponse(
                ragEnabled, hybridReady, total, 0, 0, 0, null, retrievalMode);
    }

    private int resolveMaxItems(ReferenceVideoIndexRequest request) {
        int requested = (request != null && request.maxItems() != null)
                ? request.maxItems()
                : knowledgeRagProperties.getMaxIndexItems();
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, MAX_INDEX_HARD_LIMIT);
    }

    /**
     * 拼装案例卡片文本（与 5.1c 父索引同款结构）：标题/分区/层级/标签/简介/亮点/热度/可靠质量分。
     * 该 text 在 hybrid 集合里同时服务 BM25（关键词）与 dense（语义），故保持与父卡片一致的富文本。
     * 与 {@link KnowledgeReferenceIndexService} 的同名逻辑有意各自保留（简单优先；真出现第三处再上提共用）。
     */
    private String buildDocumentText(ReferenceVideoRecord video) {
        StringBuilder builder = new StringBuilder();
        builder.append("标题：").append(TextUtil.trimToDefault(video.getTitle(), "")).append('\n');
        if (TextUtil.hasText(video.getCategory())) {
            builder.append("分区：").append(video.getCategory()).append('\n');
        }
        builder.append("案例层级：").append(tierLabel(video.getTier())).append('\n');
        if (TextUtil.hasText(video.getTags())) {
            builder.append("标签：").append(video.getTags()).append('\n');
        }
        if (TextUtil.hasText(video.getDescription())) {
            builder.append("简介：").append(video.getDescription()).append('\n');
        }
        if (TextUtil.hasText(video.getHighlightSummary())) {
            builder.append("亮点摘要：").append(video.getHighlightSummary()).append('\n');
        }
        String stats = buildStatsText(video);
        if (!stats.isEmpty()) {
            builder.append("数据：").append(stats).append('\n');
        }
        if (video.isQualityScoreReliable() && video.getQualityScore() != null) {
            builder.append("质量分：").append(video.getQualityScore());
        }
        return TextUtil.abbreviateWithSuffix(builder.toString().trim(), DOC_TEXT_MAX_CHARS, "...");
    }

    private String buildStatsText(ReferenceVideoRecord video) {
        List<String> parts = new ArrayList<>();
        if (video.getViewCount() != null) {
            parts.add("播放 " + video.getViewCount());
        }
        if (video.getLikeCount() != null) {
            parts.add("点赞 " + video.getLikeCount());
        }
        if (video.getCoinCount() != null) {
            parts.add("投币 " + video.getCoinCount());
        }
        if (video.getFavoriteCount() != null) {
            parts.add("收藏 " + video.getFavoriteCount());
        }
        if (video.getDanmakuCount() != null) {
            parts.add("弹幕 " + video.getDanmakuCount());
        }
        if (video.getReplyCount() != null) {
            parts.add("评论 " + video.getReplyCount());
        }
        return String.join("，", parts);
    }

    private String tierLabel(String tier) {
        if (tier == null) {
            return "未知";
        }
        return switch (tier) {
            case "BENCHMARK" -> "优品标杆";
            case "COMPETITOR" -> "竞品";
            case "OWN_HISTORY" -> "自己历史";
            default -> tier;
        };
    }

    private String normalizeError(Exception exception) {
        String message = exception.getMessage();
        if (TextUtil.isBlank(message)) {
            message = exception.getClass().getSimpleName();
        }
        return TextUtil.abbreviateWithSuffix(message.replaceAll("\\s+", " ").trim(), 200, "...");
    }
}
