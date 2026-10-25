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
import java.util.concurrent.locks.ReentrantLock;

/**
 * 案例库父表向量索引服务 — Pipeline 中「父卡片 → Milvus 向量」的写入器。
 * <p>
 * <b>Pipeline 角色</b>：
 * 位于「导入落库（{@link KnowledgeReferenceVideoService}）→ 语义检索」之间。
 * 把父表 {@code creator_reference_video} 的案例卡片（每视频一段富文本）写入知识库专用 Milvus 集合，
 * 并把索引状态（INDEXED / FAILED）回写到父表。这是三层检索结构中<b>最粗粒度的顶层</b>——
 * 检索时先命中父卡片获得全局上下文，再决定是否需要下钻到中块或子条目。
 * <p>
 * <b>设计范式（与反馈索引服务一致的成熟模式）</b>：
 * <ol>
 *   <li><b>MySQL 是事实源，Milvus 是可检索副本</b>：这里只写向量、回写状态，不把业务结论存进向量库。
 *       万一向量库数据丢失，MySQL 里的案例记录不丢，重新 rebuild 即可恢复。</li>
 *   <li><b>不加 {@code @Transactional}</b>：Milvus 写入不归数据库事务管。按批提交、按批回写 embedding_status，
 *       才能让状态真实反映向量库现状（否则 DB 回滚会和 Milvus 实际写入产生分叉）。</li>
 *   <li><b>部分批次失败不报错</b>：写入 failedCount 与 warnings，让用户看到具体哪几批没成功，
 *       而不是整批中断、已经成功写入的批次被浪费。</li>
 * </ol>
 * <p>
 * <b>与反馈侧的有意差异：增量而非全量</b>
 * 反馈是「按 task 全量重建」；知识库是全局且持续增长的语料，全量重嵌入既费 Embedding 又无必要，
 * 因此这里做成<b>增量</b>——只索引尚未成功索引（PENDING / FAILED）的案例。
 */
@Service
public class KnowledgeReferenceIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceIndexService.class);

    // 手动入口与自动任务共用锁，避免同一条记录同时向量化两次。
    private final ReentrantLock indexLock = new ReentrantLock();

    /**
     * 单次索引硬上限，与接口校验 @Max(1000) 对齐。
     * 二次防御配置被误改成超大值导致高成本 Embedding——1000 条案例卡片 × Qwen embedding
     * 约 200 万 token，成本约 1 元，是合理上限。
     */
    private static final int MAX_INDEX_HARD_LIMIT = 1000;

    /** 失败提示最多收集条数，避免大批失败时 warnings 列表过长撑爆响应体。 */
    private static final int MAX_WARNINGS = 10;

    /** embedding_error 列是 VARCHAR(512)，失败原因截断到 480 字符，留出省略号（...）的余量。 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 480;

    /**
     * 单个案例卡片文本上限（字符数）。
     * 4000 字 ≈ 5K~6K tokens（中英文混合），远低于 Qwen text-embedding-v4 的 8192 token 输入上限。
     * 防止个别超长简介把单条 Embedding 输入撑爆。
     */
    private static final int DOC_TEXT_MAX_CHARS = 4000;

    /** 检索模式预测值，与 5.2 检索链路保持同一套字面量，前端据此切换检索策略。 */
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
     * 重建（增量）案例库父表向量索引。
     * <p>
     * <b>执行流程</b>：
     * <ol>
     *   <li>门控检查：RAG 开关 + 向量库就绪</li>
     *   <li>取待索引案例列表（status = PENDING / FAILED）</li>
     *   <li>按批构造 Document 并写入 Milvus</li>
     *   <li>逐批回写 INDEXED（成功）或 FAILED（失败）状态</li>
     * </ol>
     * <p>
     * <b>为什么不做全量重建</b>：知识库案例持续增长，全量重嵌入成本随时间线性增长。
     * 增量只索引新案例或上次失败的案例，成本稳定在 O(增量) 而非 O(总量)。
     * <p>
     * <b>异常约定</b>：RAG 业务开关未启用 / 向量库未就绪 / 没有待索引案例 → 400；
     * Embedding 或 Milvus 部分失败不报错，写入 failedCount 与 warnings。
     *
     * @param request 索引请求，含可选的 maxItems 上限
     * @return 索引结果，含 indexed/failed 计数 + warnings
     */
    public ReferenceVideoIndexResponse rebuild(ReferenceVideoIndexRequest request) {
        return index(request, true);
    }

    public ReferenceVideoIndexResponse indexPending() {
        return index(null, false);
    }

    private ReferenceVideoIndexResponse index(ReferenceVideoIndexRequest request, boolean includeFailed) {
        if (!indexLock.tryLock()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该层索引正在处理中，请稍后刷新");
        }
        try {
            return doIndex(request, includeFailed);
        } finally {
            indexLock.unlock();
        }
    }

    private ReferenceVideoIndexResponse doIndex(ReferenceVideoIndexRequest request, boolean includeFailed) {
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

        List<ReferenceVideoRecord> videos = includeFailed
                ? knowledgeReferenceVideoMapper.listIndexableVideos(maxItems)
                : knowledgeReferenceVideoMapper.listIndexableVideos(maxItems, false);
        if (videos.isEmpty()) {
            if (!includeFailed) return new ReferenceVideoIndexResponse(
                    true, true, 0, 0, 0, 0, List.of(), LocalDateTime.now());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "没有待索引的案例（PENDING / FAILED），请先导入案例，或确认是否已全部索引完成");
        }

        List<String> warnings = new ArrayList<>();
        int indexed = 0;
        int failed = 0;
        for (int start = 0; start < videos.size(); start += batchSize) {
            if (Thread.currentThread().isInterrupted()) break;
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
                String reason = TextUtil.normalizeExceptionMessage(exception, ERROR_MESSAGE_MAX_LENGTH);
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

        // 关闭后台任务时保留未处理记录为 PENDING，返回实际未处理数量。
        return new ReferenceVideoIndexResponse(
                true,
                true,
                videos.size(),
                indexed,
                videos.size() - indexed - failed,
                failed,
                warnings,
                LocalDateTime.now()
        );
    }

    /**
     * 查询案例库向量索引状态：各状态计数 + 最近成功索引时间 + 检索模式预测。
     * <p>
     * RAG 关闭时照常返回（ragEnabled=false、vectorStoreReady=false），
     * 用于前端确认当前是否处于优雅降级状态（检索走纯 SQL 而非向量）。
     *
     * @return 索引状态，含 total/indexed/pending/failed 计数和检索模式预测（VECTOR / SQL）
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

    /**
     * 解析单次索引上限，兜底 [1, MAX_INDEX_HARD_LIMIT]。
     * 优先取请求值，否则用配置默认值。即使配置被误改成 0 或超大值也安全。
     */
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
     * 把一条案例组装成 Milvus 向量文档。
     * <p>
     * text 是供语义检索的案例卡片，评分同时说明仅用于同分区比较，避免模型把它当成全站质量结论。
     * metadata 存供 5.2 检索过滤/回查的业务字段。
     * 公开统计不完整时不写评分，分区案例数量少不会阻止基础分进入索引。
     */
    private Document toDocument(ReferenceVideoRecord video) {
        // 元数据只放有效统计，同时保留分区范围，避免调用方将修正分用于跨分区比较。
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("videoId", video.getVideoId());
        putIfNotNull(metadata, "bvId", video.getBvId());
        metadata.put("tier", video.getTier());
        putIfNotNull(metadata, "category", video.getCategory());
        metadata.put("source", video.getSource());
        metadata.put("qualityScoreReliable", video.isQualityScoreReliable());
        metadata.put("qualitySampleCount", video.getQualitySampleCount());
        metadata.put("scoreScope", "SAME_CATEGORY");
        if (video.getRawQualityScore() != null) {
            metadata.put("rawQualityScore", video.getRawQualityScore().doubleValue());
        }
        if (video.getQualityScore() != null) {
            // 转成 double 避免不同向量存储对 BigDecimal 的序列化行为不同。
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
     * 拼装案例卡片文本（Embedding 输入）。
     * <p>
     * 结构：标题 → 分区 → 案例层级 → 标签 → 简介 → 亮点摘要 → 热度数据 → 分区互动分。
     * 使用中文标签（「标题：」「分区：」「案例层级：」等），是因为 Embedding 模型能识别
     * 「标签：干货」与查询「干货视频」之间的语义关联，而不只是匹配原文片段。
     * <p>
     * 分区互动分不代表内容真实质量，写明比较范围让案例分析沿用相同口径。
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
        if (video.getQualityScore() != null) {
            builder.append("分区互动分：").append(video.getQualityScore()).append("（仅同分区比较）");
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

    /**
     * 层级枚举转中文标签（与 {@link KnowledgeReferenceChunkService} 同口径）。
     * 中文词会出现在向量文档里，帮助语义检索匹配「给我看竞品」「我自己历史的视频」这类问法。
     */
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

    /**
     * 安全 put：value 为 null 时不写入 metadata，避免 Milvus 元数据出现 null 值导致检索异常。
     */
    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

}
