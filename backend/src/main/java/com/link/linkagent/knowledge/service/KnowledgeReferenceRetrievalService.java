package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeHybridStore;
import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.config.KnowledgeVectorStore;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.QueryEnhanceStrategy;
import com.link.linkagent.knowledge.model.ReferenceVideoEvidence;
import com.link.linkagent.knowledge.model.ReferenceVideoEvidenceItem;
import com.link.linkagent.knowledge.model.ReferenceVideoItemRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoSearchRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoSearchResponse;
import com.link.linkagent.knowledge.rag.KnowledgeRerankClient;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 跨分区视频案例库检索服务（阶段 5.2a：最小检索闭环）。
 * <p>
 * <b>三段式</b>（照搬反馈侧 {@code CreatorFeedbackEvidenceRetrievalService} 的成熟范式）：
 * <ol>
 *   <li>RAG 启用且向量库就绪 → dense 语义检索父表案例卡片，拿到 videoId；</li>
 *   <li>用 videoId <b>回查父表事实源</b>（is_deleted=0），把向量库里的旧批次/已删案例过滤掉；</li>
 *   <li>命中不足、或向量库不可用/运行期异常 → SQL 关键词兜底，<b>优雅降级且不报错</b>。</li>
 * </ol>
 * <p>
 * <b>隔离约束</b>：知识库向量库不是 {@code VectorStore} 类型的 Spring Bean，必须经
 * {@link KnowledgeVectorStore} 取，<b>不能</b>注入 {@code ObjectProvider<VectorStore>}（那是反馈侧集合）。
 * <p>
 * <b>简单优先</b>：5.2a 只做 dense + SQL 兜底；查询改写/多查询/HyDE（5.2b）、子表父子召回（5.2c）、
 * 原生 BM25 混合（5.2d）、Rerank（5.2e）按切片后续叠加，本服务此刻不提前留空抽象。检索逻辑先落在
 * knowledge 模块内，待 5.3 内核统一、出现复用方时再把共性能力上提到通用 rag 包。
 */
@Service
public class KnowledgeReferenceRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceRetrievalService.class);

    /** RAG 关闭或向量库不可用：纯 SQL 关键词兜底。 */
    public static final String MODE_SQL = "SQL";
    /** 向量命中足够，并已回查父表事实源。 */
    public static final String MODE_VECTOR = "VECTOR";
    /** 向量命中不足，合并 SQL 兜底补足。 */
    public static final String MODE_VECTOR_WITH_SQL_FALLBACK = "VECTOR_WITH_SQL_FALLBACK";
    /** 原生 hybrid 命中足够（dense+BM25+RRF，5.2d-2）。 */
    public static final String MODE_HYBRID = "HYBRID";
    /** 原生 hybrid 命中不足，合并 SQL 兜底补足。 */
    public static final String MODE_HYBRID_WITH_SQL_FALLBACK = "HYBRID_WITH_SQL_FALLBACK";

    /** 单次检索候选硬上限，与接口层 @Max(50) 对齐，二次防御配置被误改成超大值。 */
    private static final int MAX_TOP_K = 50;

    /** 每张父卡片回显的子条目证据上限（5.2c-2）：防单卡证据刷屏；子召回里超出此数的命中只用于召回、不进证据。 */
    private static final int MAX_EVIDENCE_PER_VIDEO = 3;

    /** 送 rerank 的单条候选文本上限（5.2e）：远低于 qwen3-rerank 单文档 4000 token 限制，防超长卡片撑大请求。 */
    private static final int RERANK_DOC_MAX_CHARS = 1500;

    /** 允许的案例层级过滤值，与父表 tier 语义一致；非法过滤直接 400 而非静默空结果，避免误判“没数据”。 */
    private static final Set<String> ALLOWED_TIERS = Set.of("BENCHMARK", "COMPETITOR", "OWN_HISTORY");

    private final KnowledgeRagProperties knowledgeRagProperties;
    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;
    /** 知识库专用向量库（隔离 Bean）：不是 VectorStore 类型的 Spring Bean，故直接注入这个持有者。 */
    private final KnowledgeVectorStore knowledgeVectorStore;
    /** 查询增强器（5.2b）：dense 检索前把原始 query 扩展为 1~N 条检索文本。 */
    private final KnowledgeQueryEnhancer knowledgeQueryEnhancer;
    /** 重排客户端（5.2e）：检索候选定下来后用 qwen3-rerank 精排；关闭/失败时返回空、保持原序。 */
    private final KnowledgeRerankClient knowledgeRerankClient;
    /** 原生 hybrid 存储（5.2d）：hybrid 开关开且就绪时，父/子召回都改走它的 dense+BM25+RRF。 */
    private final KnowledgeHybridStore knowledgeHybridStore;

    public KnowledgeReferenceRetrievalService(KnowledgeRagProperties knowledgeRagProperties,
                                              KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                              KnowledgeVectorStore knowledgeVectorStore,
                                              KnowledgeQueryEnhancer knowledgeQueryEnhancer,
                                              KnowledgeRerankClient knowledgeRerankClient,
                                              KnowledgeHybridStore knowledgeHybridStore) {
        this.knowledgeRagProperties = knowledgeRagProperties;
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.knowledgeQueryEnhancer = knowledgeQueryEnhancer;
        this.knowledgeRerankClient = knowledgeRerankClient;
        this.knowledgeHybridStore = knowledgeHybridStore;
    }

    /**
     * 检索案例库。入参已由控制器 @Valid 完成基本校验，这里再做归一与兜底（tier 大写校验、topK 收敛、query 去空白）。
     */
    public ReferenceVideoSearchResponse search(ReferenceVideoSearchRequest request) {
        String query = request.query().trim();
        String category = TextUtil.trimToNull(request.category());
        String tier = normalizeTier(request.tier());
        int topK = resolveTopK(request.topK());
        QueryEnhanceStrategy strategy = resolveStrategy(request.strategy());

        boolean ragEnabled = knowledgeRagProperties.isEnabled();
        // 原生 hybrid 是否生效（5.2d-2）：RAG 总开关 + hybrid 子开关 + hybrid 库就绪。开则父/子召回都改走 dense+BM25+RRF。
        boolean hybridEnabled = ragEnabled
                && knowledgeRagProperties.getHybrid().isEnabled()
                && knowledgeHybridStore.isReady();
        // Spring AI 父向量库（5.2a/b 老路径的 dense 召回源）：仅 hybrid 关时用。
        VectorStore vectorStore = (ragEnabled && knowledgeVectorStore.isReady())
                ? knowledgeVectorStore.getVectorStore().orElse(null)
                : null;
        // 向量链路可用 = hybrid 就绪 或 Spring AI 父库就绪；两者都不可用才纯 SQL 兜底。
        // hybrid 关时 hybridEnabled=false，本判断退化为原来的「vectorStore==null」，零回归。
        if (!hybridEnabled && vectorStore == null) {
            // RAG 关 / 向量库都不可用：SQL 兜底用原始 query，未增强、无子证据、不重排，故回显 strategy=NONE、空 enhancedQueries/evidence、reranked=false。
            return new ReferenceVideoSearchResponse(
                    MODE_SQL, QueryEnhanceStrategy.NONE.name(), List.of(),
                    sqlFallback(query, category, tier, topK), List.of(), false);
        }

        // 查询增强（5.2b）：NONE → 单条原 query；其余 → 增强器产出 1~N 条（内部已失败降级，至少回退 [query]）。
        List<String> searchTexts = (strategy == QueryEnhanceStrategy.NONE)
                ? List.of(query)
                : knowledgeQueryEnhancer.enhance(query, strategy);
        if (searchTexts.isEmpty()) {
            searchTexts = List.of(query);
        }
        // 回显实际扩展的查询：NONE 不算增强，回显空；其余回显扩展结果，便于核对增强是否合理。
        List<String> enhancedQueries = (strategy == QueryEnhanceStrategy.NONE) ? List.of() : searchTexts;

        // 候选池宽度：开 rerank 时按更宽的 candidatePoolSize 召回（retrieve-wide → rerank → 截 topK），关时即 topK。
        boolean rerankEnabled = knowledgeRagProperties.getRerank().isEnabled();
        int candidateK = rerankEnabled
                ? Math.min(MAX_TOP_K, Math.max(topK, knowledgeRagProperties.getRerank().getCandidatePoolSize()))
                : topK;

        // 父召回：hybrid 开走原生 dense+BM25+RRF（5.2d-2），关走 Spring AI dense（5.2a/b）。运行期异常都退 SQL 兜底，让用户仍拿到结果。
        List<String> parentVideoIds;
        try {
            parentVideoIds = hybridEnabled
                    ? hybridSearchMulti(searchTexts, category, tier, candidateK)
                    : denseSearchMulti(vectorStore, searchTexts, category, tier, candidateK);
        } catch (Exception exception) {
            log.warn("案例库{}检索失败，回退 SQL 兜底。query={}", hybridEnabled ? "hybrid" : "向量",
                    TextUtil.preview(query, 60, ""), exception);
            return new ReferenceVideoSearchResponse(
                    MODE_SQL, QueryEnhanceStrategy.NONE.name(), List.of(),
                    sqlFallback(query, category, tier, topK), List.of(), false);
        }

        // 子-small-to-big 召回：hybrid 开走子集合 hybrid（5.2d-3），关走 Spring AI 子集合（5.2c-2）。
        // 独立 try，子集合异常只退「父-only」、绝不中断父检索（small-to-big 是锦上添花）。
        // childMap：videoId → 命中的 itemId（有序、每卡截到 MAX_EVIDENCE_PER_VIDEO）；keySet 喂合并、values 喂证据回查。
        LinkedHashMap<String, List<String>> childMap = new LinkedHashMap<>();
        if (hybridEnabled) {
            try {
                childMap = childHybridSearchMulti(searchTexts, category, tier, candidateK);
            } catch (Exception exception) {
                log.warn("案例库子条目 hybrid 召回失败，退回父-only。query={}", TextUtil.preview(query, 60, ""), exception);
                childMap = new LinkedHashMap<>();
            }
        } else if (knowledgeVectorStore.isChildReady()) {
            VectorStore childStore = knowledgeVectorStore.getChildVectorStore().orElse(null);
            if (childStore != null) {
                try {
                    childMap = childSearchMulti(childStore, searchTexts, category, tier, candidateK);
                } catch (Exception exception) {
                    log.warn("案例库子条目召回失败，退回父-only。query={}", TextUtil.preview(query, 60, ""), exception);
                    childMap = new LinkedHashMap<>();
                }
            }
        }

        // 合并 videoId：父在前（卡片直接匹配），子派生在后，LinkedHashSet 去重取前 candidateK（无 RRF；rerank 后再截 topK）。
        List<String> orderedVideoIds = mergeVideoIds(parentVideoIds, childMap.keySet(), candidateK);

        // 事实来源回查：向量库只给 videoId，父表才是真身；is_deleted=0 过滤掉旧批次/已删案例。
        List<ReferenceVideoRecord> vectorRecords = orderedVideoIds.isEmpty()
                ? List.of()
                : reorderByVideoIds(knowledgeReferenceVideoMapper.listByVideoIds(orderedVideoIds), orderedVideoIds);

        // 候选与模式：命中足够走 VECTOR/HYBRID；不足合并 SQL 兜底走 *_WITH_SQL_FALLBACK。候选先按 candidateK，rerank 后统一截 topK。
        // hybrid 开时模式标 HYBRID 系，便于前端/排查区分本次走的是原生混合还是纯 dense。
        String mode;
        List<ReferenceVideoRecord> candidates;
        int minHit = Math.max(1, knowledgeRagProperties.getMinVectorHitCount());
        if (vectorRecords.size() >= minHit) {
            mode = hybridEnabled ? MODE_HYBRID : MODE_VECTOR;
            candidates = vectorRecords;
        } else {
            mode = hybridEnabled ? MODE_HYBRID_WITH_SQL_FALLBACK : MODE_VECTOR_WITH_SQL_FALLBACK;
            List<ReferenceVideoRecord> sqlRecords = sqlFallbackRecords(query, category, tier, candidateK);
            candidates = mergeDistinctByVideoId(vectorRecords, sqlRecords, candidateK);
        }

        // Rerank（5.2e，可选）：用原始 query 对候选精排（不用改写/HyDE 扩展文本）；关闭/失败 → 空序、保持原序。最后统一截到 topK。
        List<Integer> rerankOrder = rerankEnabled
                ? knowledgeRerankClient.rerank(query, toRerankTexts(candidates))
                : List.of();
        boolean reranked = !rerankOrder.isEmpty();
        List<ReferenceVideoRecord> finalRecords = reranked
                ? limit(reorderByIndices(candidates, rerankOrder), topK)
                : limit(candidates, topK);

        // 证据只挂在最终结果里「有子命中」的卡片上：SQL 补进来的卡片来自关键词、天然无子证据。
        return new ReferenceVideoSearchResponse(
                mode, strategy.name(), enhancedQueries,
                toResponses(finalRecords), buildEvidence(finalRecords, childMap), reranked);
    }

    // ============================ 向量检索 + 回查 ============================

    /**
     * 多路 dense 检索（5.2b）：对查询增强产出的 1~N 条文本各做一次向量检索，按 videoId 合并去重。
     * 单条（NONE / REWRITE / HYDE）直接走 {@link #denseSearch}、零额外开销；多条（MULTI_QUERY）逐条检索后用
     * LinkedHashSet 保序去重，<b>不做 RRF 加权</b>（正式融合留 5.2d），取前 topK。
     */
    private List<String> denseSearchMulti(VectorStore vectorStore, List<String> searchTexts,
                                          String category, String tier, int topK) {
        if (searchTexts.size() == 1) {
            return denseSearch(vectorStore, searchTexts.get(0), category, tier, topK);
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String text : searchTexts) {
            merged.addAll(denseSearch(vectorStore, text, category, tier, topK));
        }
        List<String> result = new ArrayList<>(merged);
        return result.size() > topK ? new ArrayList<>(result.subList(0, topK)) : result;
    }

    /**
     * 多路 hybrid 检索（5.2d-2）：对查询增强产出的 1~N 条文本各做一次原生 dense+BM25+RRF 检索，按 videoId 合并去重。
     * 单条直接一次 {@link KnowledgeHybridStore#hybridSearch}；多条逐条检索后 LinkedHashSet 保序去重取前 topK
     * （每条内部已 RRF 融合 dense/BM25；跨增强查询这层仍不做 RRF，与 {@link #denseSearchMulti} 同口径）。
     */
    private List<String> hybridSearchMulti(List<String> searchTexts, String category, String tier, int topK) {
        if (searchTexts.size() == 1) {
            return knowledgeHybridStore.hybridSearch(searchTexts.get(0), category, tier, topK);
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String text : searchTexts) {
            merged.addAll(knowledgeHybridStore.hybridSearch(text, category, tier, topK));
        }
        List<String> result = new ArrayList<>(merged);
        return result.size() > topK ? new ArrayList<>(result.subList(0, topK)) : result;
    }

    private List<String> denseSearch(VectorStore vectorStore, String query, String category, String tier, int topK) {
        // 用 var 接住 builder：不显式写死 SearchRequest 的嵌套 builder 类型名，避免对其命名的脆弱依赖。
        var builder = SearchRequest.builder()
                .query(query)
                .topK(topK);
        Filter.Expression filter = buildFilter(category, tier);
        if (filter != null) {
            builder.filterExpression(filter);
        }
        List<Document> documents = vectorStore.similaritySearch(builder.build());
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        // 保留相似度顺序的 videoId 列表（distinct 防止同一视频被多文档命中）。
        return documents.stream()
                .map(this::extractVideoId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 按 category / tier 组合元数据过滤（任一可空）。
     * 用 {@link FilterExpressionBuilder} 构建，不手工拼字符串，避免取值里的特殊字符破坏表达式。
     * 这些 metadata 键由 5.1c 索引时写入父表向量文档（videoId/tier/category/...）。
     */
    private Filter.Expression buildFilter(String category, String tier) {
        boolean hasCategory = TextUtil.hasText(category);
        boolean hasTier = TextUtil.hasText(tier);
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        if (hasCategory && hasTier) {
            return builder.and(builder.eq("category", category), builder.eq("tier", tier)).build();
        }
        if (hasCategory) {
            return builder.eq("category", category).build();
        }
        if (hasTier) {
            return builder.eq("tier", tier).build();
        }
        return null;
    }

    private String extractVideoId(Document document) {
        if (document == null) {
            return null;
        }
        // 索引时 embedding_id 复用 video_id，且 metadata 也存了 videoId；优先读 metadata，回退文档 id。
        Object metadataVideoId = document.getMetadata() == null ? null : document.getMetadata().get("videoId");
        if (metadataVideoId != null && TextUtil.hasText(metadataVideoId.toString())) {
            return metadataVideoId.toString();
        }
        return TextUtil.trimToNull(document.getId());
    }

    /**
     * 按向量相似度顺序重排回查结果（MySQL IN 查询不保证顺序）。
     */
    private List<ReferenceVideoRecord> reorderByVideoIds(List<ReferenceVideoRecord> records, List<String> orderedVideoIds) {
        Map<String, ReferenceVideoRecord> byId = new LinkedHashMap<>();
        for (ReferenceVideoRecord record : records) {
            byId.put(record.getVideoId(), record);
        }
        List<ReferenceVideoRecord> ordered = new ArrayList<>();
        for (String videoId : orderedVideoIds) {
            ReferenceVideoRecord record = byId.get(videoId);
            if (record != null) {
                ordered.add(record);
            }
        }
        return ordered;
    }

    // ============================ 子召回 + 证据（5.2c-2 small-to-big） ============================

    /**
     * 多路子召回：对每条检索文本查子集合，按 videoId 聚合命中的 itemId（small-to-big 的 small 端）。
     * 返回 {@code LinkedHashMap<videoId, List<itemId>>}：keySet 给合并定序（子召回的 videoId），values 给证据回查。
     * 每个 videoId 的 itemId 去重并截到 {@link #MAX_EVIDENCE_PER_VIDEO}——只限<b>证据条数</b>，videoId 仍照常登记进合并。
     * 复用父侧 {@link #buildFilter}：子文档 5.2c-1 已带 category/tier 元数据，过滤口径与父检索一致。
     */
    private LinkedHashMap<String, List<String>> childSearchMulti(VectorStore childStore, List<String> searchTexts,
                                                                 String category, String tier, int topK) {
        LinkedHashMap<String, List<String>> videoToItems = new LinkedHashMap<>();
        Filter.Expression filter = buildFilter(category, tier);
        for (String text : searchTexts) {
            var builder = SearchRequest.builder().query(text).topK(topK);
            if (filter != null) {
                builder.filterExpression(filter);
            }
            List<Document> documents = childStore.similaritySearch(builder.build());
            if (documents == null) {
                continue;
            }
            for (Document document : documents) {
                // 子文档的 videoId / itemId 都在 metadata（5.2c-1 写入）。关键：不能回退 document.getId()——
                // 子文档 id 是 itemId，回退会把 itemId 误当 videoId（这正是子召回不能复用父侧 extractVideoId 的原因）。
                String videoId = extractChildMetadata(document, "videoId");
                String itemId = extractChildMetadata(document, "itemId");
                if (videoId == null || itemId == null) {
                    continue;
                }
                List<String> items = videoToItems.computeIfAbsent(videoId, key -> new ArrayList<>());
                if (items.size() < MAX_EVIDENCE_PER_VIDEO && !items.contains(itemId)) {
                    items.add(itemId);
                }
            }
        }
        return videoToItems;
    }

    /**
     * 多路子集合 hybrid 召回（5.2d-3）：对每条检索文本查子 hybrid 集合，按 videoId 聚合命中 itemId（small-to-big 的 small 端）。
     * 与 {@link #childSearchMulti} 唯一差异是召回源换成 {@link KnowledgeHybridStore#childHybridSearch}（dense+BM25+RRF）；
     * 聚合口径完全一致：videoId→itemId 有序、每卡截 {@link #MAX_EVIDENCE_PER_VIDEO}、itemId 去重。
     */
    private LinkedHashMap<String, List<String>> childHybridSearchMulti(List<String> searchTexts,
                                                                       String category, String tier, int topK) {
        LinkedHashMap<String, List<String>> videoToItems = new LinkedHashMap<>();
        for (String text : searchTexts) {
            for (KnowledgeHybridStore.HybridChildHit hit : knowledgeHybridStore.childHybridSearch(text, category, tier, topK)) {
                String videoId = hit.videoId();
                String itemId = hit.itemId();
                if (videoId == null || itemId == null) {
                    continue;
                }
                List<String> items = videoToItems.computeIfAbsent(videoId, key -> new ArrayList<>());
                if (items.size() < MAX_EVIDENCE_PER_VIDEO && !items.contains(itemId)) {
                    items.add(itemId);
                }
            }
        }
        return videoToItems;
    }

    /**
     * 只从 metadata 取子文档字段，<b>不回退</b> {@code document.getId()}（子文档 id 是 itemId，回退会污染 videoId）。
     */
    private String extractChildMetadata(Document document, String key) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        Object value = document.getMetadata().get(key);
        return (value != null && TextUtil.hasText(value.toString())) ? value.toString() : null;
    }

    /**
     * 合并父 / 子召回的 videoId：父在前、子在后，{@link LinkedHashSet} 去重保序，取前 topK（无 RRF，正式融合留 5.2d）。
     */
    private List<String> mergeVideoIds(List<String> parentVideoIds, Set<String> childVideoIds, int topK) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(parentVideoIds);
        merged.addAll(childVideoIds);
        List<String> result = new ArrayList<>(merged);
        return result.size() > topK ? new ArrayList<>(result.subList(0, topK)) : result;
    }

    /**
     * 组装证据：只对最终卡片中「有子命中」者，按 childMap 的 itemId 回查子表事实源（is_deleted=0）后挂上。
     * 顺序跟随 finalRecords（与 items 一致，前端按 videoId 一一对应）；回查后为空的卡片跳过（子条目可能已软删）。
     */
    private List<ReferenceVideoEvidence> buildEvidence(List<ReferenceVideoRecord> finalRecords,
                                                       LinkedHashMap<String, List<String>> childMap) {
        if (childMap.isEmpty() || finalRecords.isEmpty()) {
            return List.of();
        }
        // 汇总最终卡片涉及的全部 itemId，一次性回查，避免逐卡查库。
        List<String> allItemIds = new ArrayList<>();
        for (ReferenceVideoRecord record : finalRecords) {
            List<String> itemIds = childMap.get(record.getVideoId());
            if (itemIds != null) {
                allItemIds.addAll(itemIds);
            }
        }
        if (allItemIds.isEmpty()) {
            return List.of();
        }
        Map<String, ReferenceVideoItemRecord> byItemId = new LinkedHashMap<>();
        for (ReferenceVideoItemRecord item : knowledgeReferenceVideoMapper.listItemsByItemIds(allItemIds)) {
            byItemId.put(item.getItemId(), item);
        }
        List<ReferenceVideoEvidence> evidence = new ArrayList<>();
        for (ReferenceVideoRecord record : finalRecords) {
            List<String> itemIds = childMap.get(record.getVideoId());
            if (itemIds == null) {
                continue;
            }
            List<ReferenceVideoEvidenceItem> items = new ArrayList<>();
            for (String itemId : itemIds) {
                ReferenceVideoItemRecord item = byItemId.get(itemId);
                if (item != null) {
                    items.add(new ReferenceVideoEvidenceItem(
                            item.getItemId(), item.getContent(), item.getSentiment(), item.getSourceType()));
                }
            }
            if (!items.isEmpty()) {
                evidence.add(new ReferenceVideoEvidence(record.getVideoId(), items));
            }
        }
        return evidence;
    }

    // ============================ Rerank 精排（5.2e） ============================

    /** 把候选父卡片转成送 rerank 的文本（与 candidates 顺序一一对应，下标即 rerank 返回的 index）。 */
    private List<String> toRerankTexts(List<ReferenceVideoRecord> records) {
        return records.stream().map(this::buildRerankText).toList();
    }

    /**
     * 拼装送 rerank 的卡片文本：标题 + 分区 + 亮点摘要 + 标签——给精排模型判断「这张案例卡片是否切题」的语义主体。
     * 截到 {@link #RERANK_DOC_MAX_CHARS}，远低于 qwen3-rerank 单文档 token 上限，防超长简介撑大请求。
     */
    private String buildRerankText(ReferenceVideoRecord record) {
        StringBuilder builder = new StringBuilder();
        builder.append("标题：").append(TextUtil.trimToDefault(record.getTitle(), "")).append('\n');
        if (TextUtil.hasText(record.getCategory())) {
            builder.append("分区：").append(record.getCategory()).append('\n');
        }
        if (TextUtil.hasText(record.getHighlightSummary())) {
            builder.append("亮点：").append(record.getHighlightSummary()).append('\n');
        }
        if (TextUtil.hasText(record.getTags())) {
            builder.append("标签：").append(record.getTags());
        }
        return TextUtil.abbreviateWithSuffix(builder.toString().trim(), RERANK_DOC_MAX_CHARS, "...");
    }

    /**
     * 按 rerank 返回的下标顺序重排候选（order 里是 candidates 的原始下标）。
     * 越界下标已在客户端过滤，这里再做一次防御；rerank 省略 top_n 时返回全量，故重排后规模不变。
     */
    private List<ReferenceVideoRecord> reorderByIndices(List<ReferenceVideoRecord> candidates, List<Integer> order) {
        List<ReferenceVideoRecord> result = new ArrayList<>();
        for (Integer index : order) {
            if (index != null && index >= 0 && index < candidates.size()) {
                result.add(candidates.get(index));
            }
        }
        return result;
    }

    // ============================ SQL 关键词兜底 ============================

    private List<ReferenceVideoResponse> sqlFallback(String query, String category, String tier, int topK) {
        return toResponses(sqlFallbackRecords(query, category, tier, topK));
    }

    private List<ReferenceVideoRecord> sqlFallbackRecords(String query, String category, String tier, int topK) {
        // 5.2a 最简策略：折叠空白后整串 LIKE；为空则退化为「按质量分取前 N」（mapper 内 keyword 为 null 即不加关键词条件）。
        // 刻意不做中文 2/3-gram 打分：父表是「案例卡片」粒度、量级小，整串 LIKE + 质量分排序已够；
        // 真正的关键词/分词召回留给 5.2d 原生 BM25，避免在兜底里堆注定被替换的临时分词逻辑。
        String keyword = TextUtil.trimToNull(TextUtil.collapseWhitespace(query));
        return knowledgeReferenceVideoMapper.searchByKeyword(category, tier, keyword, topK);
    }

    // ============================ 合并 / 截断 / 组装 ============================

    private List<ReferenceVideoRecord> mergeDistinctByVideoId(List<ReferenceVideoRecord> primary,
                                                              List<ReferenceVideoRecord> secondary,
                                                              int limit) {
        Map<String, ReferenceVideoRecord> merged = new LinkedHashMap<>();
        for (ReferenceVideoRecord record : primary) {
            if (record.getVideoId() != null) {
                merged.putIfAbsent(record.getVideoId(), record);
            }
        }
        for (ReferenceVideoRecord record : secondary) {
            if (merged.size() >= limit) {
                break;
            }
            if (record.getVideoId() != null) {
                merged.putIfAbsent(record.getVideoId(), record);
            }
        }
        return limit(new ArrayList<>(merged.values()), limit);
    }

    private List<ReferenceVideoRecord> limit(List<ReferenceVideoRecord> records, int limit) {
        if (records.size() <= limit) {
            return records;
        }
        return new ArrayList<>(records.subList(0, limit));
    }

    private List<ReferenceVideoResponse> toResponses(List<ReferenceVideoRecord> records) {
        return records.stream().map(ReferenceVideoResponse::from).toList();
    }

    // ============================ 入参归一 ============================

    /**
     * tier 过滤大写归一 + 白名单校验；非法值直接 400（与列表接口口径一致）。
     */
    private String normalizeTier(String tier) {
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

    /**
     * topK 为空用配置默认值；即使配置或入参越界，也用 [1, MAX_TOP_K] 收敛，二次防御候选过多。
     */
    private int resolveTopK(Integer requested) {
        int value = (requested != null) ? requested : knowledgeRagProperties.getTopK();
        if (value < 1) {
            return 1;
        }
        return Math.min(value, MAX_TOP_K);
    }

    /**
     * 查询增强策略归一（5.2b）：未指定用配置默认（默认 REWRITE）；指定则大写归一 + 枚举校验，非法直接 400（与 tier 同口径）。
     */
    private QueryEnhanceStrategy resolveStrategy(String requested) {
        String value = TextUtil.trimToNull(requested);
        if (value == null) {
            return knowledgeRagProperties.getQueryEnhancement().getStrategy();
        }
        try {
            return QueryEnhanceStrategy.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的查询增强策略: " + requested);
        }
    }
}
