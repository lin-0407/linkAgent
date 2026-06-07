package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.config.KnowledgeVectorStore;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.QueryEnhanceStrategy;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoSearchRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoSearchResponse;
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

    /** 单次检索候选硬上限，与接口层 @Max(50) 对齐，二次防御配置被误改成超大值。 */
    private static final int MAX_TOP_K = 50;

    /** 允许的案例层级过滤值，与父表 tier 语义一致；非法过滤直接 400 而非静默空结果，避免误判“没数据”。 */
    private static final Set<String> ALLOWED_TIERS = Set.of("BENCHMARK", "COMPETITOR", "OWN_HISTORY");

    private final KnowledgeRagProperties knowledgeRagProperties;
    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;
    /** 知识库专用向量库（隔离 Bean）：不是 VectorStore 类型的 Spring Bean，故直接注入这个持有者。 */
    private final KnowledgeVectorStore knowledgeVectorStore;
    /** 查询增强器（5.2b）：dense 检索前把原始 query 扩展为 1~N 条检索文本。 */
    private final KnowledgeQueryEnhancer knowledgeQueryEnhancer;

    public KnowledgeReferenceRetrievalService(KnowledgeRagProperties knowledgeRagProperties,
                                              KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                              KnowledgeVectorStore knowledgeVectorStore,
                                              KnowledgeQueryEnhancer knowledgeQueryEnhancer) {
        this.knowledgeRagProperties = knowledgeRagProperties;
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.knowledgeQueryEnhancer = knowledgeQueryEnhancer;
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
        // 只有业务开关打开且向量库就绪时才取向量库；否则连基础设施都不碰，零额外成本。
        VectorStore vectorStore = (ragEnabled && knowledgeVectorStore.isReady())
                ? knowledgeVectorStore.getVectorStore().orElse(null)
                : null;
        if (vectorStore == null) {
            // RAG 关 / 向量库不可用：SQL 兜底用原始 query，未做增强，故回显 strategy=NONE、enhancedQueries 空。
            return new ReferenceVideoSearchResponse(
                    MODE_SQL, QueryEnhanceStrategy.NONE.name(), List.of(),
                    sqlFallback(query, category, tier, topK));
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

        List<String> orderedVideoIds;
        try {
            orderedVideoIds = denseSearchMulti(vectorStore, searchTexts, category, tier, topK);
        } catch (Exception exception) {
            // 向量检索的连接/维度/超时等运行期异常不应中断检索；记录后回退 SQL 兜底，让用户仍拿到结果。
            log.warn("案例库向量检索失败，回退 SQL 兜底。query={}", TextUtil.preview(query, 60, ""), exception);
            return new ReferenceVideoSearchResponse(
                    MODE_SQL, QueryEnhanceStrategy.NONE.name(), List.of(),
                    sqlFallback(query, category, tier, topK));
        }

        // 事实来源回查：向量库只给 videoId，父表才是真身；is_deleted=0 过滤掉旧批次/已删案例。
        List<ReferenceVideoRecord> vectorRecords = orderedVideoIds.isEmpty()
                ? List.of()
                : reorderByVideoIds(knowledgeReferenceVideoMapper.listByVideoIds(orderedVideoIds), orderedVideoIds);

        int minHit = Math.max(1, knowledgeRagProperties.getMinVectorHitCount());
        if (vectorRecords.size() >= minHit) {
            return new ReferenceVideoSearchResponse(
                    MODE_VECTOR, strategy.name(), enhancedQueries, toResponses(limit(vectorRecords, topK)));
        }

        // 命中不足：向量结果在前（语义更相关），SQL 兜底补后，按 videoId 去重后截断。
        List<ReferenceVideoRecord> sqlRecords = sqlFallbackRecords(query, category, tier, topK);
        List<ReferenceVideoRecord> merged = mergeDistinctByVideoId(vectorRecords, sqlRecords, topK);
        return new ReferenceVideoSearchResponse(
                MODE_VECTOR_WITH_SQL_FALLBACK, strategy.name(), enhancedQueries, toResponses(merged));
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
