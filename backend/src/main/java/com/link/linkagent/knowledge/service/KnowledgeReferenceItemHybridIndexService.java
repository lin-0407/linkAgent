package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeHybridStore;
import com.link.linkagent.knowledge.config.KnowledgeHybridStore.HybridChildDoc;
import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoIndexStatusResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoItemIndexRow;
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
 * 案例库<b>子条目</b>原生 hybrid 索引服务 — hybrid 架构的 small 端索引器。
 * <p>
 * <b>Pipeline 角色</b>：
 * 与 {@link KnowledgeReferenceItemIndexService}（Spring AI 子集合）平行，但使用自建 schema 的 Milvus hybrid 子集合
 * （dense 稠密向量 + BM25 稀疏向量）。当 hybrid 开关开启时，检索走本集合的 small-to-big 召回，命中更准。
 * <p>
 * <b>与父 hybrid 索引（{@link KnowledgeReferenceHybridIndexService}）结构平行、有意分开</b>：集合不同（子 hybrid 集合）、
 * 文本不同（子条目原文 vs 父卡片富文本）、主键不同（item_id vs video_id）。整库重灌、手动 embedding、双开关门控、
 * 部分失败降级——范式与父 hybrid 完全一致；仅两个 hybrid 索引器，暂不抽公共骨架（简单优先，真出现第三个再上提）。
 * <p>
 * <b>不回写 embedding_status</b>：那套状态属于 5.2c-1 的 Spring AI 子集合；子 hybrid 是独立集合、整库重灌，不共享该状态机。
 * <p>
 * <b>整库重灌 vs 增量</b>：与父 hybrid 一样，子 hybrid 也是 drop 重建 + 全量灌入。子条目量级远超父表（数千条 vs 数百条），
 * 但原理相同——MySQL 是事实源，Milvus 是派生副本，全量重灌简单可复现。增量优化留后续。
 */
@Service
public class KnowledgeReferenceItemHybridIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceItemHybridIndexService.class);

    /** 单次重灌硬上限，与接口层 @Max(1000) 对齐。子条目量级大，可经 maxItems 调大（上限 1000）。 */
    private static final int MAX_INDEX_HARD_LIMIT = 1000;

    /** 失败提示最多收集条数。 */
    private static final int MAX_WARNINGS = 10;

    /** 单条子文档文本上限：子条目是评论 / 弹幕短文，与 5.2c-1 子索引一致取 1000。 */
    private static final int ITEM_DOC_TEXT_MAX_CHARS = 1000;

    /** 检索模式预测值：子 hybrid 就绪且有子条目时预计走 HYBRID，否则 SQL。 */
    private static final String MODE_HYBRID = "HYBRID";
    private static final String MODE_SQL = "SQL";

    private final KnowledgeRagProperties knowledgeRagProperties;
    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;
    private final KnowledgeHybridStore knowledgeHybridStore;

    public KnowledgeReferenceItemHybridIndexService(KnowledgeRagProperties knowledgeRagProperties,
                                                    KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                                    KnowledgeHybridStore knowledgeHybridStore) {
        this.knowledgeRagProperties = knowledgeRagProperties;
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
        this.knowledgeHybridStore = knowledgeHybridStore;
    }

    /**
     * 重建（整库重灌）子条目 hybrid 索引。
     * <p>
     * <b>执行流程</b>：
     * <ol>
     *   <li>双开关门控：RAG 启用 + hybrid 子开关启用 + hybrid 库就绪</li>
     *   <li>取所有未删子条目（JOIN 父表确保父案例存活，反范式带出 category/tier）</li>
     *   <li>drop 旧子 hybrid 集合 → 自建 schema 建子集合（失败整体 400）</li>
     *   <li>按批构造 HybridChildDoc 并灌入 Milvus 子 hybrid 集合</li>
     * </ol>
     * <p>
     * <b>注意</b>：不看 embedding_status——子 hybrid 是独立集合，与 Spring AI 子集合状态机无关。
     *
     * @param request 索引请求，含可选的 maxItems 上限
     * @return 索引结果，含 indexed/failed 计数 + warnings
     */
    public ReferenceVideoIndexResponse rebuildChildHybrid(ReferenceVideoIndexRequest request) {
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

        // 重灌源：所有未删子条目（JOIN 父表存活，反范式带出 category/tier）。不看 embedding_status——子 hybrid 是独立集合。
        List<ReferenceVideoItemIndexRow> rows = knowledgeReferenceVideoMapper.listAllItemsForHybrid(maxItems);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "没有可灌入子 hybrid 的子条目，请先采集导入含优质评论弹幕的案例");
        }

        // 先 drop 重建子集合：失败则整体 400（没有集合后续插入无意义）。
        try {
            knowledgeHybridStore.recreateChildCollection();
        } catch (Exception exception) {
            log.error("重建子 hybrid 集合失败。", exception);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "重建子 hybrid 集合失败：" + TextUtil.normalizeExceptionMessage(exception, 200));
        }

        List<String> warnings = new ArrayList<>();
        int indexed = 0;
        int failed = 0;
        for (int start = 0; start < rows.size(); start += batchSize) {
            int end = Math.min(start + batchSize, rows.size());
            List<ReferenceVideoItemIndexRow> chunk = rows.subList(start, end);
            try {
                List<HybridChildDoc> docs = chunk.stream()
                        .map(row -> new HybridChildDoc(
                                row.getItemId(), row.getVideoId(), buildItemText(row), row.getCategory(), row.getTier()))
                        .toList();
                try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库 hybrid 子条目索引")) {
                    indexed += knowledgeHybridStore.insertChildDocs(docs);
                }
            } catch (Exception exception) {
                failed += chunk.size();
                if (warnings.size() < MAX_WARNINGS) {
                    warnings.add("第 " + (start + 1) + "-" + end + " 条子条目灌入失败："
                            + TextUtil.normalizeExceptionMessage(exception, 200));
                }
                log.warn("子 hybrid 灌入失败。range={}-{}", start + 1, end, exception);
            }
        }

        return new ReferenceVideoIndexResponse(
                true, true, rows.size(), indexed, 0, failed, warnings, LocalDateTime.now());
    }

    /**
     * 子 hybrid 索引状态：双开关 + 子 hybrid 库就绪 + 可重灌子条目总数 + 检索模式预测。
     * <p>
     * 整库重灌、不在 MySQL 维护 per-item 状态，故 indexed/pending/failed 恒 0；
     * totalCount 是重灌源的未删子条目总数（JOIN 父表存活）。
     *
     * @return 索引状态，检索模式预测为 HYBRID 或 SQL
     */
    public ReferenceVideoIndexStatusResponse childHybridStatus() {
        boolean ragEnabled = knowledgeRagProperties.isEnabled();
        boolean hybridReady = ragEnabled
                && knowledgeRagProperties.getHybrid().isEnabled()
                && knowledgeHybridStore.isReady();
        long total = knowledgeReferenceVideoMapper.countItemsForHybrid();
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
     * 子 hybrid 文档文本——子条目原文（评论/弹幕）本身。
     * <p>
     * 与 5.2c-1 子索引（{@link KnowledgeReferenceItemIndexService}）同口径——small 端要
     * 「这条具体内容」的精确语义和 BM25 关键词匹配，不堆父级字段（那是父 hybrid 文档的职责）。
     * 在 hybrid 集合里，dense 向量捕捉语义相似度，BM25 稀疏向量按中文关键词精确匹配，
     * 两者互补——这正是 hybrid 相比纯 dense 的优势所在。
     */
    private String buildItemText(ReferenceVideoItemIndexRow row) {
        String content = TextUtil.trimToDefault(row.getContent(), "");
        return TextUtil.abbreviateWithSuffix(content, ITEM_DOC_TEXT_MAX_CHARS, "...");
    }

}
