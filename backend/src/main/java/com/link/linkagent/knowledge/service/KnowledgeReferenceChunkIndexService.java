package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.config.KnowledgeVectorStore;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoChunkRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoChunkIndexRow;
import com.link.linkagent.knowledge.model.ReferenceVideoEmbeddingStatusCount;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexStatusResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoItemRecord;
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
 * 案例库主题中块向量索引服务 — Pipeline 中「三层分块」中间层的索引器。
 * <p>
 * <b>Pipeline 角色</b>：
 * 位于「中块生成（{@link KnowledgeReferenceChunkService}）→ 向量检索」之间。
 * 把 {@code creator_reference_video_chunk} 推入独立的 Milvus 中块集合，并回写 embedding_status。
 * 它是三层检索结构中<b>最主要的检索战场</b>——创作者问「标题怎么包装」「内容怎么定位」「观众反馈了什么」时，
 * 检索优先命中中块层，再按需回查父卡片（上下文）或子条目（原文证据）。
 * <p>
 * <b>特有职责：历史案例中块补齐</b>
 * 与父索引服务（{@link KnowledgeReferenceIndexService}）的纯增量不同，本服务会在 rebuildChunks 时先检查
 * 是否有历史视频缺中块（backfillMissingChunks），这是为了兼容「先有父表和子表、后来才引入中块层」的升级路径。
 * <p>
 * <b>与父索引服务的关系</b>：结构平行（同款增量写入 + 批失败降级），但集合不同（chunk 集合）、文本构造不同（中块文档）。
 * 二者有意分开——中块多了 backfill 步骤和 chunkType 标签处理，合并到父索引服务会破坏其单一职责。
 */
@Service
public class KnowledgeReferenceChunkIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceChunkIndexService.class);

    /** 单次索引硬上限，与接口校验 @Max(1000) 对齐，二次防御高成本 Embedding。 */
    private static final int MAX_INDEX_HARD_LIMIT = 1000;

    /** 失败提示最多收集条数，避免大批失败时 warnings 列表过长。 */
    private static final int MAX_WARNINGS = 10;

    /** embedding_error 列是 VARCHAR(512)，失败原因截断到 480，留出省略号（...）的余量。 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 480;

    /**
     * 单个中块文档文本上限。
     * 与 {@link KnowledgeReferenceChunkService} 保持一致（1800），
     * 因为中块的 chunkContent 在生成时已截断过，这里再截一次是二次防御。
     */
    private static final int CHUNK_DOC_TEXT_MAX_CHARS = 1800;

    /** 检索模式预测值：向量可用且有索引记录时预计走向量，否则走 SQL 降级。 */
    private static final String MODE_VECTOR = "VECTOR";
    private static final String MODE_SQL = "SQL";

    private final KnowledgeRagProperties knowledgeRagProperties;
    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;
    private final KnowledgeVectorStore knowledgeVectorStore;
    private final KnowledgeReferenceChunkService knowledgeReferenceChunkService;

    public KnowledgeReferenceChunkIndexService(KnowledgeRagProperties knowledgeRagProperties,
                                               KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                               KnowledgeVectorStore knowledgeVectorStore,
                                               KnowledgeReferenceChunkService knowledgeReferenceChunkService) {
        this.knowledgeRagProperties = knowledgeRagProperties;
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.knowledgeReferenceChunkService = knowledgeReferenceChunkService;
    }

    /**
     * 重建（增量）主题中块向量索引，含历史案例中块补齐。
     * <p>
     * <b>执行流程</b>：
     * <ol>
     *   <li>门控检查：RAG 开关 + 中块向量库就绪</li>
     *   <li>backfillMissingChunks：为历史缺少中块的视频补齐中块（升级兼容路径）</li>
     *   <li>取待索引中块列表（PENDING / FAILED）</li>
     *   <li>按批写入 Milvus 中块集合，逐批回写 INDEXED / FAILED 状态</li>
     * </ol>
     * <p>
     * <b>异常约定</b>：RAG 未启用 / 中块向量库未就绪 / 无待索引中块 → 400；
     * 批次失败不整体回滚，只回写失败状态与 warnings。
     *
     * @param request 索引请求，含 maxItems 可选上限
     * @return 索引结果，含 indexed/failed 计数和 warnings 列表
     */
    public ReferenceVideoIndexResponse rebuildChunks(ReferenceVideoIndexRequest request) {
        if (!knowledgeRagProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "知识库 RAG 业务开关未启用，请先设置 knowledge.rag.enabled=true");
        }
        if (!knowledgeVectorStore.isChunkReady()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "知识库主题中块向量库未就绪，请确认 Milvus 已连接、配置了 EmbeddingModel，且中块集合初始化成功");
        }
        VectorStore chunkStore = knowledgeVectorStore.getChunkVectorStore()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识库主题中块向量库未就绪"));

        int maxItems = resolveMaxItems(request);
        int batchSize = Math.max(1, knowledgeRagProperties.getIndexBatchSize());
        List<String> warnings = new ArrayList<>();
        backfillMissingChunks(maxItems, warnings);
        List<ReferenceVideoChunkIndexRow> rows = knowledgeReferenceVideoMapper.listIndexableChunks(maxItems);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "没有待索引的主题中块（PENDING / FAILED），请先导入案例，或确认是否已全部索引完成");
        }

        int indexed = 0;
        int failed = 0;
        for (int start = 0; start < rows.size(); start += batchSize) {
            int end = Math.min(start + batchSize, rows.size());
            List<ReferenceVideoChunkIndexRow> chunk = rows.subList(start, end);
            try {
                List<Document> documents = chunk.stream().map(this::toChunkDocument).toList();
                try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库主题中块向量索引")) {
                    chunkStore.add(documents);
                }
                for (ReferenceVideoChunkIndexRow row : chunk) {
                    knowledgeReferenceVideoMapper.updateChunkEmbeddingIndexed(row.getChunkId(), row.getChunkId());
                    indexed++;
                }
            } catch (Exception exception) {
                String reason = TextUtil.normalizeExceptionMessage(exception, ERROR_MESSAGE_MAX_LENGTH);
                for (ReferenceVideoChunkIndexRow row : chunk) {
                    knowledgeReferenceVideoMapper.updateChunkEmbeddingFailed(row.getChunkId(), reason);
                    failed++;
                }
                if (warnings.size() < MAX_WARNINGS) {
                    warnings.add("第 " + (start + 1) + "-" + end + " 条主题中块索引失败：" + reason);
                }
                log.warn("主题中块向量索引失败。range={}-{}", start + 1, end, exception);
            }
        }

        return new ReferenceVideoIndexResponse(
                true, true, rows.size(), indexed, 0, failed, warnings, LocalDateTime.now());
    }

    /**
     * 为历史案例补齐主题中块——兼容「先有父表/子表、后来才引入中块层」的升级路径。
     * <p>
     * 这一步放在中块索引服务而非导入服务里，是因为它<b>只服务于三层分块索引升级</b>：
     * 不改变原导入接口的响应契约，只在本服务首次 rebuild 时静默补齐。
     * 补齐逻辑查询「缺中块的视频」→ 逐视频生成中块 → 插入 chunk 表 → 后续增量索引自然覆盖。
     * 单个视频补齐失败不中断整体（记 warning），保证一批历史案例能尽可能多地补齐。
     */
    private void backfillMissingChunks(int limit, List<String> warnings) {
        List<ReferenceVideoRecord> videos = knowledgeReferenceVideoMapper.listVideosMissingChunks(limit);
        if (videos.isEmpty()) {
            return;
        }
        int generated = 0;
        for (ReferenceVideoRecord video : videos) {
            try {
                List<ReferenceVideoItemRecord> items =
                        knowledgeReferenceVideoMapper.listItemsByVideoId(video.getVideoId(), 200);
                for (ReferenceVideoChunkRecord chunk : knowledgeReferenceChunkService.buildChunks(video, items)) {
                    knowledgeReferenceVideoMapper.insertReferenceVideoChunk(chunk);
                    generated++;
                }
            } catch (Exception exception) {
                String reason = TextUtil.normalizeExceptionMessage(exception, ERROR_MESSAGE_MAX_LENGTH);
                if (warnings.size() < MAX_WARNINGS) {
                    warnings.add("视频 " + video.getVideoId() + " 主题中块补齐失败：" + reason);
                }
                log.warn("历史视频主题中块补齐失败。videoId={}", video.getVideoId(), exception);
            }
        }
        if (generated > 0) {
            log.info("已为历史案例补齐主题中块：videos={}, chunks={}", videos.size(), generated);
        }
    }

    /**
     * 查询主题中块向量索引状态。
     * <p>
     * RAG 关闭时照常返回（ragEnabled=false、chunkReady=false），用于前端确认中块层是否处于降级状态
     * （即检索是否退回到纯 SQL 模式）。
     *
     * @return 索引状态，含各状态计数、最近索引时间、检索模式预测（VECTOR / SQL）
     */
    public ReferenceVideoIndexStatusResponse chunkStatus() {
        boolean ragEnabled = knowledgeRagProperties.isEnabled();
        boolean chunkReady = ragEnabled && knowledgeVectorStore.isChunkReady();

        long total = 0;
        long indexed = 0;
        long pending = 0;
        long failed = 0;
        for (ReferenceVideoEmbeddingStatusCount row : knowledgeReferenceVideoMapper.countChunkEmbeddingStatus()) {
            long count = row.getCount();
            total += count;
            switch (TextUtil.trimToDefault(row.getStatus(), "")) {
                case "INDEXED" -> indexed += count;
                case "PENDING" -> pending += count;
                case "FAILED" -> failed += count;
                default -> {
                    // 其它状态仅计入 total，避免响应结构为少见状态扩张。
                }
            }
        }

        LocalDateTime lastIndexedAt = knowledgeReferenceVideoMapper.findLastChunkEmbeddingUpdateTime();
        String retrievalMode = (chunkReady && indexed > 0) ? MODE_VECTOR : MODE_SQL;
        return new ReferenceVideoIndexStatusResponse(
                ragEnabled, chunkReady, total, indexed, pending, failed, lastIndexedAt, retrievalMode);
    }

    /**
     * 将中块行组装成 Milvus 向量文档。metadata 存 chunkId/videoId/chunkType 等过滤键，
     * text 由 buildChunkDocumentText 构造（带类型标签 + 标题 + 内容）。
     */
    private Document toChunkDocument(ReferenceVideoChunkIndexRow row) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunkId", row.getChunkId());
        metadata.put("videoId", row.getVideoId());
        metadata.put("chunkType", row.getChunkType());
        putIfNotNull(metadata, "chunkTitle", row.getChunkTitle());
        putIfNotNull(metadata, "category", row.getCategory());
        putIfNotNull(metadata, "tier", row.getTier());
        return Document.builder()
                .id(row.getChunkId())
                .text(buildChunkDocumentText(row))
                .metadata(metadata)
                .build();
    }

    /**
     * 中块文档文本构造——显式带类型和标题，为语义检索加锚。
     * <p>
     * 为什么加「主题类型」和「主题标题」两个显式标签：
     * 创作者问「标题怎么包装」「内容定位」「观众反馈了什么」时，这些标签会直接出现在向量文档中，
     * Embedding 模型能识别「主题类型：标题包装」与 query 「标题怎么取」之间的语义关联，
     * 大幅提升命中正确主题层的概率。
     */
    private String buildChunkDocumentText(ReferenceVideoChunkIndexRow row) {
        StringBuilder builder = new StringBuilder();
        builder.append("主题类型：").append(chunkTypeLabel(row.getChunkType())).append('\n');
        if (TextUtil.hasText(row.getChunkTitle())) {
            builder.append("主题标题：").append(row.getChunkTitle()).append('\n');
        }
        builder.append(TextUtil.trimToDefault(row.getChunkContent(), ""));
        return TextUtil.abbreviateWithSuffix(builder.toString().trim(), CHUNK_DOC_TEXT_MAX_CHARS, "...");
    }

    /**
     * 将中块类型枚举转为中文标签，用于向量文档里的「主题类型：」行。
     */
    private String chunkTypeLabel(String chunkType) {
        if (chunkType == null) {
            return "未知主题";
        }
        return switch (chunkType) {
            case "TITLE_PACKAGE" -> "标题包装";
            case "CONTENT_POSITIONING" -> "内容定位";
            case "AUDIENCE_FEEDBACK_SUMMARY" -> "观众反馈";
            default -> chunkType;
        };
    }

    /**
     * 解析单次索引上限：优先取请求值，否则用配置默认值，兜底 [1, 1000]。
     * 即使配置被误改成 0 或超大值也安全。
     */
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
     * 安全 put：value 为 null 时不写入 metadata，避免 Milvus 元数据出现 null 值。
     */
    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

}
