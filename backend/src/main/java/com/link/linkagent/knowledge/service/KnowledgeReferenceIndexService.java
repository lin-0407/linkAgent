package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.config.KnowledgeVectorStore;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoEmbeddingStatusCount;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexStatusResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 案例库向量索引服务（阶段 5.1c）。
 * <p>
 * 把父表「案例卡片」（每视频一段卡片文本）写入知识库专用 Milvus 集合，并把索引状态回写到 creator_reference_video。
 * 子表评论弹幕的向量化与父子召回留给 5.2，本阶段只索引父表（每视频 1 个向量）。
 * <p>
 * 设计要点（与反馈索引服务一致的成熟范式）：
 * <ol>
 *   <li>MySQL 是事实来源，Milvus 只是「可被语义检索的候选副本」：这里只写向量、回写状态，不把业务结论存进向量库。</li>
 *   <li>不加 {@code @Transactional}：Milvus 写入不归数据库事务管，按批提交、按批回写 embedding_status，
 *       才能让状态真实反映向量库现状（否则 DB 回滚会和 Milvus 实际写入分叉）。</li>
 *   <li>部分批次失败不报错，而是写入 failedCount 与 warnings，让用户看到具体哪几批没成功。</li>
 * </ol>
 * <p>
 * 与反馈范式的<b>有意差异</b>：反馈是「按 task 全量重建」；知识库是全局且持续增长的语料，全量重嵌入既费 Embedding 又无必要，
 * 因此这里做成<b>增量</b>——只索引尚未成功索引（PENDING / FAILED）的案例（见 mapper.listIndexableVideos）。
 */
@Service
public class KnowledgeReferenceIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceIndexService.class);

    /** 单次索引硬上限，和接口校验 @Max(1000) 对齐，二次防御配置被误改成超大值导致高成本 Embedding。 */
    private static final int MAX_INDEX_HARD_LIMIT = 1000;

    /** 失败提示最多收集条数，避免大批失败时 warnings 过长。 */
    private static final int MAX_WARNINGS = 10;

    /** embedding_error 列是 VARCHAR(512)，失败原因摘要截断到 480，留出省略号余量。 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 480;

    /** 单个案例卡片文本上限，防止个别超长简介把单条 Embedding 输入撑爆（Qwen 有输入上限）。 */
    private static final int DOC_TEXT_MAX_CHARS = 4000;

    /** 检索模式预测值，与未来 5.2 检索链路保持同一套字面量。 */
    private static final String MODE_VECTOR = "VECTOR";
    private static final String MODE_SQL = "SQL";

    private final KnowledgeRagProperties knowledgeRagProperties;
    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;
    /** 知识库专用向量库（隔离 Bean）：不是 VectorStore 类型的 Spring Bean，故直接注入这个持有者。 */
    private final KnowledgeVectorStore knowledgeVectorStore;

    public KnowledgeReferenceIndexService(KnowledgeRagProperties knowledgeRagProperties,
                                          KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                          KnowledgeVectorStore knowledgeVectorStore) {
        this.knowledgeRagProperties = knowledgeRagProperties;
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
        this.knowledgeVectorStore = knowledgeVectorStore;
    }

    /**
     * 重建（增量）案例库向量索引。
     * <p>
     * 异常约定：RAG 业务开关未启用 / 向量库未就绪 / 没有待索引案例 → 400；
     * Embedding 或 Milvus 部分失败不报错，而是写入 failedCount 与 warnings。
     */
    public ReferenceVideoIndexResponse rebuild(ReferenceVideoIndexRequest request) {
        if (!knowledgeRagProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "知识库 RAG 业务开关未启用，请先设置 knowledge.rag.enabled=true");
        }
        if (!knowledgeVectorStore.isReady()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "知识库向量库未就绪，请确认 Milvus 已连接且配置了 EmbeddingModel（EMBEDDING_MODEL_TYPE=openai）");
        }
        VectorStore vectorStore = knowledgeVectorStore.getVectorStore()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识库向量库未就绪"));

        int maxItems = resolveMaxItems(request);
        // 批大小取配置值（默认 10，Qwen text-embedding-v3/v4 兼容模式单批硬上限）；兜底至少 1，防误配 0 死循环
        int batchSize = Math.max(1, knowledgeRagProperties.getIndexBatchSize());

        List<ReferenceVideoRecord> videos = knowledgeReferenceVideoMapper.listIndexableVideos(maxItems);
        if (videos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "没有待索引的案例（PENDING / FAILED），请先导入案例，或确认是否已全部索引完成");
        }

        List<String> warnings = new ArrayList<>();
        int indexed = 0;
        int failed = 0;
        for (int start = 0; start < videos.size(); start += batchSize) {
            int end = Math.min(start + batchSize, videos.size());
            List<ReferenceVideoRecord> chunk = videos.subList(start, end);
            try {
                List<Document> documents = chunk.stream().map(this::toDocument).toList();
                // 一次 add 把整批文本一起向量化，比逐条写大幅减少 Embedding 调用次数
                try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库案例向量索引")) {
                    vectorStore.add(documents);
                }
                for (ReferenceVideoRecord video : chunk) {
                    // embedding_id 复用 video_id，让向量文档与父表案例天然一一对应，5.2 回查时无需额外映射
                    knowledgeReferenceVideoMapper.updateVideoEmbeddingIndexed(video.getVideoId(), video.getVideoId());
                    indexed++;
                }
            } catch (Exception exception) {
                String reason = normalizeError(exception);
                for (ReferenceVideoRecord video : chunk) {
                    knowledgeReferenceVideoMapper.updateVideoEmbeddingFailed(video.getVideoId(), reason);
                    failed++;
                }
                if (warnings.size() < MAX_WARNINGS) {
                    warnings.add("第 " + (start + 1) + "-" + end + " 条索引失败：" + reason);
                }
                log.warn("案例向量索引失败。range={}-{}", start + 1, end, exception);
            }
        }

        // skippedCount 恒为 0：候选已在 SQL 层按 is_deleted + 状态过滤，没有「查出来又跳过」的情况；
        // 字段保留是为了和反馈索引响应结构一致、便于前端复用。
        return new ReferenceVideoIndexResponse(
                true,
                true,
                videos.size(),
                indexed,
                0,
                failed,
                warnings,
                LocalDateTime.now()
        );
    }

    /**
     * 查询案例库向量索引状态：各状态计数 + 最近成功索引时间 + 检索模式预测。
     * RAG 关闭时照常返回（ragEnabled=false、vectorStoreReady=false），用于前端确认优雅降级是否生效。
     */
    public ReferenceVideoIndexStatusResponse status() {
        boolean ragEnabled = knowledgeRagProperties.isEnabled();
        boolean vectorStoreReady = ragEnabled && knowledgeVectorStore.isReady();

        long total = 0;
        long indexed = 0;
        long pending = 0;
        long failed = 0;
        for (ReferenceVideoEmbeddingStatusCount row : knowledgeReferenceVideoMapper.countEmbeddingStatus()) {
            long count = row.getCount();
            total += count;
            switch (TextUtil.trimToDefault(row.getStatus(), "")) {
                case "INDEXED" -> indexed += count;
                case "PENDING" -> pending += count;
                case "FAILED" -> failed += count;
                default -> {
                    // SKIPPED 等其它状态只计入 total，不单列，避免响应字段无限扩张。
                }
            }
        }

        LocalDateTime lastIndexedAt = knowledgeReferenceVideoMapper.findLastEmbeddingUpdateTime();
        String retrievalMode = (vectorStoreReady && indexed > 0) ? MODE_VECTOR : MODE_SQL;

        return new ReferenceVideoIndexStatusResponse(
                ragEnabled,
                vectorStoreReady,
                total,
                indexed,
                pending,
                failed,
                lastIndexedAt,
                retrievalMode
        );
    }

    private int resolveMaxItems(ReferenceVideoIndexRequest request) {
        // maxItems 为空用配置默认值；即使配置被误改成 0 或超大值，也用 [1, 1000] 兜底，二次防御高成本 Embedding。
        int requested = (request != null && request.maxItems() != null)
                ? request.maxItems()
                : knowledgeRagProperties.getMaxIndexItems();
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, MAX_INDEX_HARD_LIMIT);
    }

    /**
     * 把一条案例组装成向量文档：文本是供语义检索的「案例卡片」，metadata 存供 5.2 过滤/排查的业务字段。
     */
    private Document toDocument(ReferenceVideoRecord video) {
        // metadata 只放非空值，避免 Milvus 元数据出现 null；这些字段为 5.2 的分区/层级/质量分过滤打底
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("videoId", video.getVideoId());
        putIfNotNull(metadata, "bvId", video.getBvId());
        metadata.put("tier", video.getTier());
        putIfNotNull(metadata, "category", video.getCategory());
        metadata.put("source", video.getSource());
        metadata.put("qualityScoreReliable", video.isQualityScoreReliable());
        metadata.put("qualitySampleCount", video.getQualitySampleCount());
        if (video.getRawQualityScore() != null) {
            metadata.put("rawQualityScore", video.getRawQualityScore().doubleValue());
        }
        if (video.isQualityScoreReliable() && video.getQualityScore() != null) {
            // 转 double 入元数据：避免 BigDecimal 在 JSON 元数据序列化上的歧义；不可靠分不写入，避免向量侧误用少样本相对分。
            metadata.put("qualityScore", video.getQualityScore().doubleValue());
        }
        putIfNotNull(metadata, "viewCount", video.getViewCount());
        putIfNotNull(metadata, "likeCount", video.getLikeCount());
        putIfNotNull(metadata, "coinCount", video.getCoinCount());
        putIfNotNull(metadata, "favoriteCount", video.getFavoriteCount());
        putIfNotNull(metadata, "publishTimeText", video.getPublishTimeText());
        return Document.builder()
                .id(video.getVideoId())
                .text(buildDocumentText(video))
                .metadata(metadata)
                .build();
    }

    /**
     * 拼装案例卡片文本：标题/分区/层级/标签/简介/亮点摘要 + 热度数据 + 可靠质量分。
     * 带中文字段名，是为了让语义检索能匹配「同赛道高互动案例」这类问法，而不只是匹配原文片段。
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

    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private String normalizeError(Exception exception) {
        String message = exception.getMessage();
        if (TextUtil.isBlank(message)) {
            message = exception.getClass().getSimpleName();
        }
        return TextUtil.abbreviateWithSuffix(message.replaceAll("\\s+", " ").trim(), ERROR_MESSAGE_MAX_LENGTH, "...");
    }
}
