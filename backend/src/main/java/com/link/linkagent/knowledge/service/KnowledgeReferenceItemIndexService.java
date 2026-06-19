package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.config.KnowledgeVectorStore;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoEmbeddingStatusCount;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexStatusResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoItemIndexRow;
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
 * 案例库<b>子条目</b>向量索引服务（阶段 5.2c-1：子表向量化）。
 * <p>
 * 把子表 {@code creator_reference_video_item}（清洗后优质评论 / 弹幕）的原文写入知识库专用的<b>子集合</b>
 * （与父集合物理隔离），并把索引状态回写到子表 {@code embedding_*}。子条目是 small-to-big 召回的 small 端：
 * 小而精的子文档命中更准，命中后由 5.2c-2 检索侧按 {@code videoId} 扩展回父表案例卡片（big）。
 * <p>
 * <b>与父索引服务（{@link KnowledgeReferenceIndexService}）的关系</b>：结构平行但<b>有意分开</b>——
 * 二者表 / 集合 / 文本构造各不相同（父是「卡片」、子是「评论弹幕原文」），分开保持各自单一职责、父服务零改动；
 * 仅两个索引器、暂不抽公共骨架（简单优先，真出现第三个再上提）。
 * <p>
 * 成熟范式照搬父侧：MySQL 是事实源、Milvus 只是可被语义检索的副本；<b>不加 {@code @Transactional}</b>
 * （Milvus 写入不归 DB 事务）；部分批次失败不报错，而是写入 failedCount 与 warnings；增量只索引 PENDING / FAILED。
 */
@Service
public class KnowledgeReferenceItemIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceItemIndexService.class);

    /** 单次索引硬上限，与接口校验 @Max(1000) 对齐，二次防御配置被误改成超大值导致高成本 Embedding。 */
    private static final int MAX_INDEX_HARD_LIMIT = 1000;

    /** 失败提示最多收集条数，避免大批失败时 warnings 过长。 */
    private static final int MAX_WARNINGS = 10;

    /** embedding_error 列是 VARCHAR(512)，失败原因摘要截断到 480，留出省略号余量。 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 480;

    /** 单条子文档文本上限：子条目是评论 / 弹幕短文，上限远小于父卡片的 4000，防个别超长评论撑爆单条 Embedding。 */
    private static final int ITEM_DOC_TEXT_MAX_CHARS = 1000;

    /** 检索模式预测值，与检索链路保持同一套字面量。 */
    private static final String MODE_VECTOR = "VECTOR";
    private static final String MODE_SQL = "SQL";

    private final KnowledgeRagProperties knowledgeRagProperties;
    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;
    /** 知识库专用向量库（隔离 Bean）：子集合也藏在它内部，经 getChildVectorStore() 取。 */
    private final KnowledgeVectorStore knowledgeVectorStore;

    public KnowledgeReferenceItemIndexService(KnowledgeRagProperties knowledgeRagProperties,
                                              KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                              KnowledgeVectorStore knowledgeVectorStore) {
        this.knowledgeRagProperties = knowledgeRagProperties;
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
        this.knowledgeVectorStore = knowledgeVectorStore;
    }

    /**
     * 重建（增量）子条目向量索引。
     * <p>
     * 异常约定：RAG 业务开关未启用 / 子向量库未就绪 / 没有待索引子条目 → 400；
     * Embedding 或 Milvus 部分失败不报错，而是写入 failedCount 与 warnings。
     */
    public ReferenceVideoIndexResponse rebuildItems(ReferenceVideoIndexRequest request) {
        if (!knowledgeRagProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "知识库 RAG 业务开关未启用，请先设置 knowledge.rag.enabled=true");
        }
        // 注意：这里查的是子向量库就绪位（isChildReady），与父向量库（isReady）独立——子集合没建好不连累父检索。
        if (!knowledgeVectorStore.isChildReady()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "知识库子条目向量库未就绪，请确认 Milvus 已连接、配置了 EmbeddingModel，且子集合初始化成功");
        }
        VectorStore childStore = knowledgeVectorStore.getChildVectorStore()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识库子条目向量库未就绪"));

        int maxItems = resolveMaxItems(request);
        // 批大小取配置值（默认 10，Qwen text-embedding-v3/v4 兼容模式单批硬上限）；兜底至少 1，防误配 0 死循环。
        int batchSize = Math.max(1, knowledgeRagProperties.getIndexBatchSize());

        List<ReferenceVideoItemIndexRow> rows = knowledgeReferenceVideoMapper.listIndexableItems(maxItems);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "没有待索引的子条目（PENDING / FAILED），请先采集导入含优质评论弹幕的案例，或确认是否已全部索引完成");
        }

        List<String> warnings = new ArrayList<>();
        int indexed = 0;
        int failed = 0;
        for (int start = 0; start < rows.size(); start += batchSize) {
            int end = Math.min(start + batchSize, rows.size());
            List<ReferenceVideoItemIndexRow> chunk = rows.subList(start, end);
            try {
                List<Document> documents = chunk.stream().map(this::toItemDocument).toList();
                // 一次 add 把整批文本一起向量化，比逐条写大幅减少 Embedding 调用次数。
                try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库子条目向量索引")) {
                    childStore.add(documents);
                }
                for (ReferenceVideoItemIndexRow row : chunk) {
                    // embedding_id 复用 item_id，让子向量文档与子表条目一一对应，5.2c-2 回查证据时无需额外映射。
                    knowledgeReferenceVideoMapper.updateItemEmbeddingIndexed(row.getItemId(), row.getItemId());
                    indexed++;
                }
            } catch (Exception exception) {
                String reason = normalizeError(exception);
                for (ReferenceVideoItemIndexRow row : chunk) {
                    knowledgeReferenceVideoMapper.updateItemEmbeddingFailed(row.getItemId(), reason);
                    failed++;
                }
                if (warnings.size() < MAX_WARNINGS) {
                    warnings.add("第 " + (start + 1) + "-" + end + " 条子条目索引失败：" + reason);
                }
                log.warn("子条目向量索引失败。range={}-{}", start + 1, end, exception);
            }
        }

        // skippedCount 恒为 0：候选已在 SQL 层按 is_deleted + 状态 + 父表存活过滤，没有「查出来又跳过」的情况；
        // 字段保留是为了与父索引响应结构一致、便于前端复用同一套展示。
        return new ReferenceVideoIndexResponse(
                true,
                true,
                rows.size(),
                indexed,
                0,
                failed,
                warnings,
                LocalDateTime.now()
        );
    }

    /**
     * 查询子条目向量索引状态：各状态计数 + 最近成功索引时间 + 检索模式预测。
     * RAG 关闭时照常返回（ragEnabled=false、vectorStoreReady=false），用于前端确认子向量索引的优雅降级是否生效。
     */
    public ReferenceVideoIndexStatusResponse itemStatus() {
        boolean ragEnabled = knowledgeRagProperties.isEnabled();
        boolean childReady = ragEnabled && knowledgeVectorStore.isChildReady();

        long total = 0;
        long indexed = 0;
        long pending = 0;
        long failed = 0;
        for (ReferenceVideoEmbeddingStatusCount row : knowledgeReferenceVideoMapper.countItemEmbeddingStatus()) {
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

        LocalDateTime lastIndexedAt = knowledgeReferenceVideoMapper.findLastItemEmbeddingUpdateTime();
        // vectorStoreReady 字段在子状态语义下映射「子向量库是否就绪」；retrievalMode 是「若现在子召回，预计走向量还是降级」。
        String retrievalMode = (childReady && indexed > 0) ? MODE_VECTOR : MODE_SQL;

        return new ReferenceVideoIndexStatusResponse(
                ragEnabled,
                childReady,
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
        // 复用父侧 max-index-items 配置：子条目量级更大，但本刀先共享同一成本护栏，需独立调参再加专用配置（简单优先）。
        int requested = (request != null && request.maxItems() != null)
                ? request.maxItems()
                : knowledgeRagProperties.getMaxIndexItems();
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, MAX_INDEX_HARD_LIMIT);
    }

    /**
     * 把一条子条目组装成向量文档：文本是评论 / 弹幕原文（small 端要精确语义），metadata 存 small-to-big 扩展键与过滤键。
     */
    private Document toItemDocument(ReferenceVideoItemIndexRow row) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        // videoId 是 small-to-big 扩展回父表案例卡片的关键键，必存。
        metadata.put("videoId", row.getVideoId());
        metadata.put("itemId", row.getItemId());
        putIfNotNull(metadata, "sentiment", row.getSentiment());
        putIfNotNull(metadata, "sourceType", row.getSourceType());
        // category/tier 由父表反范式带入：供 5.2c-2 子召回复用与父检索同款的元数据过滤（子表本身无这两列）。
        putIfNotNull(metadata, "category", row.getCategory());
        putIfNotNull(metadata, "tier", row.getTier());
        return Document.builder()
                .id(row.getItemId())
                .text(buildItemDocumentText(row))
                .metadata(metadata)
                .build();
    }

    /**
     * 子文档文本就是评论 / 弹幕原文本身——small 端要的是「这条具体内容」的精确语义，
     * 不堆叠父级标题 / 简介等字段（那是父卡片文档的职责，堆进来反而稀释 small 的精确性）。
     */
    private String buildItemDocumentText(ReferenceVideoItemIndexRow row) {
        String content = TextUtil.trimToDefault(row.getContent(), "");
        return TextUtil.abbreviateWithSuffix(content, ITEM_DOC_TEXT_MAX_CHARS, "...");
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
