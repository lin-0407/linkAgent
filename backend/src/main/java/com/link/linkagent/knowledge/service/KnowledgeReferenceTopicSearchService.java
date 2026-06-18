package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.config.KnowledgeVectorStore;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.QueryEnhanceStrategy;
import com.link.linkagent.knowledge.model.ReferenceVideoAnalysisContextResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoChunkRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoEvidenceItem;
import com.link.linkagent.knowledge.model.ReferenceVideoItemRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoMatchedTopic;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoTopicSearchRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoTopicSearchResponse;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主题优先案例检索服务。
 * <p>
 * 这条链路把 RAG 的职责收窄为“先找到相关主题中块”，视频卡片展示再按质量分排序，
 * 避免父视频大块检索把整体相似误当成创作者真正关心的主题相似。
 */
@Service
public class KnowledgeReferenceTopicSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceTopicSearchService.class);

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 5;
    private static final int MAX_PAGE = 4;
    private static final int MAX_SIZE = 5;
    private static final int MAX_TOPIC_CANDIDATES = 20;
    private static final int MAX_TOPIC_DOCUMENT_CANDIDATES = MAX_TOPIC_CANDIDATES * 3;
    private static final int MAX_CONTEXT_EVIDENCE_ITEMS = 30;
    private static final int TOPIC_PREVIEW_MAX_CHARS = 240;
    private static final String MODE_TOPIC_VECTOR = "TOPIC_VECTOR";
    private static final String MODE_SQL = "SQL";
    private static final Set<String> ALLOWED_TIERS = Set.of("BENCHMARK", "COMPETITOR", "OWN_HISTORY");

    private final KnowledgeRagProperties knowledgeRagProperties;
    private final KnowledgeVectorStore knowledgeVectorStore;
    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;
    private final KnowledgeQueryEnhancer knowledgeQueryEnhancer;

    public KnowledgeReferenceTopicSearchService(KnowledgeRagProperties knowledgeRagProperties,
                                                KnowledgeVectorStore knowledgeVectorStore,
                                                KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                                KnowledgeQueryEnhancer knowledgeQueryEnhancer) {
        this.knowledgeRagProperties = knowledgeRagProperties;
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
        this.knowledgeQueryEnhancer = knowledgeQueryEnhancer;
    }

    /**
     * 主题优先检索：先查中块集合，再把命中的视频按质量分分页返回。
     */
    public ReferenceVideoTopicSearchResponse topicSearch(ReferenceVideoTopicSearchRequest request) {
        String query = request.query().trim();
        String category = TextUtil.trimToNull(request.category());
        String tier = normalizeTier(request.tier());
        int page = resolvePage(request.page());
        int size = resolveSize(request.size());
        QueryEnhanceStrategy strategy = resolveStrategy(request.strategy());

        if (!knowledgeRagProperties.isEnabled() || !knowledgeVectorStore.isChunkReady()) {
            return sqlFallback(query, category, tier, page, size);
        }
        VectorStore chunkStore = knowledgeVectorStore.getChunkVectorStore()
                .orElse(null);
        if (chunkStore == null) {
            return sqlFallback(query, category, tier, page, size);
        }

        List<String> searchTexts = (strategy == QueryEnhanceStrategy.NONE)
                ? List.of(query)
                : knowledgeQueryEnhancer.enhance(query, strategy);
        if (searchTexts.isEmpty()) {
            searchTexts = List.of(query);
        }
        List<String> enhancedQueries = (strategy == QueryEnhanceStrategy.NONE) ? List.of() : searchTexts;

        List<ReferenceVideoMatchedTopic> matchedTopics;
        try {
            matchedTopics = searchMatchedTopics(chunkStore, searchTexts, category, tier, MAX_TOPIC_DOCUMENT_CANDIDATES);
        } catch (Exception exception) {
            log.warn("主题优先检索中块召回失败，退回 SQL 质量分兜底。query={}", TextUtil.preview(query, 60, ""), exception);
            return sqlFallback(query, category, tier, page, size);
        }

        List<String> candidateVideoIds = matchedTopics.stream()
                .map(ReferenceVideoMatchedTopic::videoId)
                .filter(TextUtil::hasText)
                .distinct()
                .limit(MAX_TOPIC_CANDIDATES)
                .toList();
        if (candidateVideoIds.isEmpty()) {
            return new ReferenceVideoTopicSearchResponse(
                    MODE_TOPIC_VECTOR, strategy.name(), enhancedQueries, page, size, MAX_PAGE, false, matchedTopics, List.of());
        }
        List<ReferenceVideoResponse> rankedCards = knowledgeReferenceVideoMapper
                .listByVideoIdsOrderByQuality(candidateVideoIds, category, tier, 0, MAX_TOPIC_CANDIDATES)
                .stream()
                .map(ReferenceVideoResponse::from)
                .toList();
        int offset = (page - 1) * size;
        List<ReferenceVideoResponse> cards = rankedCards.stream()
                .skip(offset)
                .limit(size)
                .toList();
        List<String> currentPageVideoIds = cards.stream()
                .map(ReferenceVideoResponse::videoId)
                .toList();
        List<ReferenceVideoMatchedTopic> visibleMatchedTopics = matchedTopics.stream()
                .filter(topic -> currentPageVideoIds.contains(topic.videoId()))
                .limit(MAX_TOPIC_CANDIDATES)
                .toList();
        boolean hasMore = page < MAX_PAGE && rankedCards.size() > offset + cards.size();
        return new ReferenceVideoTopicSearchResponse(
                MODE_TOPIC_VECTOR, strategy.name(), enhancedQueries, page, size, MAX_PAGE, hasMore, visibleMatchedTopics, cards);
    }

    /**
     * 点击视频卡片后加载该视频的分析上下文。
     * 这里直接读 MySQL 事实源，不再要求用户手动开启 RAG。
     */
    public ReferenceVideoAnalysisContextResponse analysisContext(String videoId) {
        String normalizedVideoId = TextUtil.trimToNull(videoId);
        if (normalizedVideoId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "videoId 不能为空");
        }
        List<ReferenceVideoRecord> videos = knowledgeReferenceVideoMapper.listByVideoIds(List.of(normalizedVideoId));
        if (videos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到对应的视频案例");
        }
        List<ReferenceVideoMatchedTopic> topics = knowledgeReferenceVideoMapper.listChunksByVideoId(normalizedVideoId)
                .stream()
                .map(this::toMatchedTopic)
                .toList();
        List<ReferenceVideoEvidenceItem> evidenceItems =
                knowledgeReferenceVideoMapper.listEvidenceItemsByVideoId(normalizedVideoId, MAX_CONTEXT_EVIDENCE_ITEMS)
                        .stream()
                        .map(this::toEvidenceItem)
                        .toList();
        return new ReferenceVideoAnalysisContextResponse(
                ReferenceVideoResponse.from(videos.get(0)), topics, evidenceItems);
    }

    private List<ReferenceVideoMatchedTopic> searchMatchedTopics(VectorStore chunkStore,
                                                                 List<String> searchTexts,
                                                                 String category,
                                                                 String tier,
                                                                 int topK) {
        Filter.Expression filter = buildFilter(category, tier);
        Map<String, ReferenceVideoMatchedTopic> byChunkId = new LinkedHashMap<>();
        for (String text : searchTexts) {
            var builder = SearchRequest.builder().query(text).topK(topK);
            if (filter != null) {
                builder.filterExpression(filter);
            }
            List<Document> documents = chunkStore.similaritySearch(builder.build());
            if (documents == null) {
                continue;
            }
            for (Document document : documents) {
                ReferenceVideoMatchedTopic topic = toMatchedTopic(document);
                if (topic != null) {
                    byChunkId.putIfAbsent(topic.chunkId(), topic);
                }
            }
        }
        return new ArrayList<>(byChunkId.values());
    }

    private ReferenceVideoMatchedTopic toMatchedTopic(Document document) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        Map<String, Object> metadata = document.getMetadata();
        String chunkId = metadataText(metadata, "chunkId");
        String videoId = metadataText(metadata, "videoId");
        if (!TextUtil.hasText(chunkId) || !TextUtil.hasText(videoId)) {
            return null;
        }
        return new ReferenceVideoMatchedTopic(
                chunkId,
                videoId,
                metadataText(metadata, "chunkType"),
                metadataText(metadata, "chunkTitle"),
                TextUtil.preview(document.getText(), TOPIC_PREVIEW_MAX_CHARS, "")
        );
    }

    private ReferenceVideoMatchedTopic toMatchedTopic(ReferenceVideoChunkRecord record) {
        return new ReferenceVideoMatchedTopic(
                record.getChunkId(),
                record.getVideoId(),
                record.getChunkType(),
                record.getChunkTitle(),
                TextUtil.preview(record.getChunkContent(), TOPIC_PREVIEW_MAX_CHARS, "")
        );
    }

    private ReferenceVideoEvidenceItem toEvidenceItem(ReferenceVideoItemRecord record) {
        return new ReferenceVideoEvidenceItem(
                record.getItemId(),
                record.getContent(),
                record.getSentiment(),
                record.getSourceType()
        );
    }

    private ReferenceVideoTopicSearchResponse sqlFallback(String query, String category, String tier, int page, int size) {
        int offset = (page - 1) * size;
        int limit = Math.min(MAX_TOPIC_CANDIDATES, offset + size);
        List<ReferenceVideoRecord> records = knowledgeReferenceVideoMapper.searchByKeyword(category, tier, query, limit);
        List<ReferenceVideoResponse> cards = records.stream()
                .skip(offset)
                .limit(size)
                .map(ReferenceVideoResponse::from)
                .toList();
        boolean hasMore = page < MAX_PAGE && records.size() > offset + cards.size();
        return new ReferenceVideoTopicSearchResponse(
                MODE_SQL, QueryEnhanceStrategy.NONE.name(), List.of(), page, size, MAX_PAGE, hasMore, List.of(), cards);
    }

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

    private String metadataText(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? null : TextUtil.trimToNull(value.toString());
    }

    private int resolvePage(Integer requested) {
        int page = requested == null ? DEFAULT_PAGE : requested;
        return Math.min(MAX_PAGE, Math.max(1, page));
    }

    private int resolveSize(Integer requested) {
        int size = requested == null ? DEFAULT_SIZE : requested;
        return Math.min(MAX_SIZE, Math.max(1, size));
    }

    private String normalizeTier(String tier) {
        String normalized = TextUtil.trimToNull(tier);
        if (normalized == null) {
            return null;
        }
        String upper = normalized.toUpperCase();
        if (!ALLOWED_TIERS.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "案例层级只能是 BENCHMARK、COMPETITOR 或 OWN_HISTORY");
        }
        return upper;
    }

    private QueryEnhanceStrategy resolveStrategy(String requested) {
        String value = TextUtil.trimToNull(requested);
        if (value == null) {
            return knowledgeRagProperties.getQueryEnhancement().getStrategy();
        }
        try {
            return QueryEnhanceStrategy.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "查询增强策略只能是 NONE、REWRITE、HYDE 或 MULTI_QUERY");
        }
    }
}
