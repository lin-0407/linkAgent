package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeHybridStore;
import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.knowledge.config.KnowledgeVectorStore;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.QueryEnhanceStrategy;
import com.link.linkagent.knowledge.model.ReferenceVideoAnalysisContextResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoChunkRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoEvidence;
import com.link.linkagent.knowledge.model.ReferenceVideoEvidenceItem;
import com.link.linkagent.knowledge.model.ReferenceVideoItemRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoMatchedTopic;
import com.link.linkagent.knowledge.model.ReferenceVideoRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoTopicSearchRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoTopicSearchResponse;
import com.link.linkagent.knowledge.rag.KnowledgeRerankClient;
import com.link.linkagent.settings.service.RuntimeSettingService;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final int MAX_RELEVANT_EVIDENCE_PER_VIDEO = 4;
    private static final int MAX_RELEVANT_EVIDENCE_CANDIDATES = MAX_TOPIC_CANDIDATES * MAX_RELEVANT_EVIDENCE_PER_VIDEO * 2;
    private static final int MAX_FALLBACK_EVIDENCE_PER_SOURCE = 2;
    private static final int TOPIC_PREVIEW_MAX_CHARS = 240;
    private static final int RERANK_TOPIC_MAX_CHARS = 720;
    private static final int RERANK_CARD_MAX_CHARS = 900;
    private static final int RERANK_EVIDENCE_MAX_CHARS = 260;
    private static final String MODE_TOPIC_VECTOR = "TOPIC_VECTOR";
    private static final String MODE_TOPIC_HYBRID = "TOPIC_HYBRID";
    private static final String MODE_SQL = "SQL";
    private static final Pattern SQL_KEYWORD_PATTERN = Pattern.compile("[\\p{IsHan}A-Za-z0-9]+");
    private static final Set<String> ALLOWED_TIERS = Set.of("BENCHMARK", "COMPETITOR", "OWN_HISTORY");

    private final KnowledgeRagProperties knowledgeRagProperties;
    private final KnowledgeVectorStore knowledgeVectorStore;
    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;
    private final KnowledgeHybridStore knowledgeHybridStore;
    private final KnowledgeQueryEnhancer knowledgeQueryEnhancer;
    private final KnowledgeRerankClient knowledgeRerankClient;
    private final RuntimeSettingService runtimeSettingService;

    public KnowledgeReferenceTopicSearchService(KnowledgeRagProperties knowledgeRagProperties,
                                                KnowledgeVectorStore knowledgeVectorStore,
                                                KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                                KnowledgeHybridStore knowledgeHybridStore,
                                                KnowledgeQueryEnhancer knowledgeQueryEnhancer,
                                                KnowledgeRerankClient knowledgeRerankClient,
                                                RuntimeSettingService runtimeSettingService) {
        this.knowledgeRagProperties = knowledgeRagProperties;
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
        this.knowledgeHybridStore = knowledgeHybridStore;
        this.knowledgeQueryEnhancer = knowledgeQueryEnhancer;
        this.knowledgeRerankClient = knowledgeRerankClient;
        this.runtimeSettingService = runtimeSettingService;
    }

    /**
     * 主题优先检索：先查中块集合，再用质量分形成候选池，最后按运行期设置决定是否结合评论弹幕 rerank。
     */
    public ReferenceVideoTopicSearchResponse topicSearch(ReferenceVideoTopicSearchRequest request) {
        String query = request.query().trim();
        String category = TextUtil.trimToNull(request.category());
        String tier = normalizeTier(request.tier());
        int page = resolvePage(request.page());
        int size = resolveSize(request.size());
        QueryEnhanceStrategy strategy = resolveStrategy(request.strategy());

        boolean ragEnabled = knowledgeRagProperties.isEnabled();
        boolean chunkEnabled = ragEnabled && knowledgeVectorStore.isChunkReady();
        boolean hybridEnabled = ragEnabled
                && knowledgeRagProperties.getHybrid().isEnabled()
                && knowledgeHybridStore.isReady();
        if (!chunkEnabled && !hybridEnabled) {
            return sqlFallback(query, category, tier, page, size);
        }
        VectorStore chunkStore = chunkEnabled ? knowledgeVectorStore.getChunkVectorStore().orElse(null) : null;
        if (chunkStore == null && !hybridEnabled) {
            return sqlFallback(query, category, tier, page, size);
        }

        List<String> searchTexts = (strategy == QueryEnhanceStrategy.NONE)
                ? List.of(query)
                : knowledgeQueryEnhancer.enhance(query, strategy);
        if (searchTexts.isEmpty()) {
            searchTexts = List.of(query);
        }
        List<String> enhancedQueries = (strategy == QueryEnhanceStrategy.NONE) ? List.of() : searchTexts;

        List<ReferenceVideoMatchedTopic> matchedTopics = List.of();
        if (chunkStore != null) {
            try {
                matchedTopics = searchMatchedTopics(chunkStore, searchTexts, category, tier, MAX_TOPIC_DOCUMENT_CANDIDATES);
            } catch (Exception exception) {
                if (!hybridEnabled) {
                    log.warn("主题优先检索中块召回失败，退回 SQL 质量分兜底。query={}", TextUtil.preview(query, 60, ""), exception);
                    return sqlFallback(query, category, tier, page, size);
                }
                // hybrid 已就绪时，中块异常不应直接丢掉 BM25+dense 的补召回能力。
                log.warn("主题优先检索中块召回失败，继续使用 hybrid 补召回。query={}", TextUtil.preview(query, 60, ""), exception);
            }
        }

        List<String> topicVideoIds = matchedTopics.stream()
                .map(ReferenceVideoMatchedTopic::videoId)
                .filter(TextUtil::hasText)
                .distinct()
                .limit(MAX_TOPIC_CANDIDATES)
                .toList();
        List<String> hybridVideoIds = List.of();
        Map<String, List<String>> hybridEvidenceItemIds = Map.of();
        if (hybridEnabled) {
            try {
                hybridVideoIds = hybridSearchMulti(searchTexts, category, tier, MAX_TOPIC_CANDIDATES);
            } catch (Exception exception) {
                log.warn("主题优先检索父 hybrid 召回失败，仅使用中块结果。query={}", TextUtil.preview(query, 60, ""), exception);
            }
            try {
                hybridEvidenceItemIds = childHybridSearchMulti(searchTexts, category, tier, MAX_RELEVANT_EVIDENCE_CANDIDATES);
            } catch (Exception exception) {
                log.warn("主题优先检索子 hybrid 召回失败，继续使用评论弹幕 dense/SQL 证据。query={}",
                        TextUtil.preview(query, 60, ""), exception);
            }
        }
        List<String> candidateVideoIds = mergeCandidateVideoIds(
                topicVideoIds, hybridVideoIds, hybridEvidenceItemIds.keySet(), MAX_TOPIC_CANDIDATES);
        if (candidateVideoIds.isEmpty()) {
            // 向量库“正常但零命中”不能直接返回空，因为用户可能输入的是标题 / BV / 强关键词；
            // 此时 SQL 关键词兜底更符合案例检索的可用性预期，也能暴露“中块索引未覆盖”的真实状态。
            log.info("主题优先检索中块/hybrid 均零命中，退回 SQL 质量分兜底。query={}", TextUtil.preview(query, 60, ""));
            return sqlFallback(query, category, tier, page, size);
        }
        List<ReferenceVideoRecord> qualityRankedRecords = knowledgeReferenceVideoMapper
                .listByVideoIdsOrderByQuality(candidateVideoIds, category, tier, 0, MAX_TOPIC_CANDIDATES);
        if (qualityRankedRecords.isEmpty()) {
            // 中块 metadata 可能滞后于 MySQL 父表过滤条件；回查不到父卡片时继续兜底，避免向量脏数据让前端空屏。
            log.info("主题优先检索中块命中但父表回查为空，退回 SQL 质量分兜底。query={}, candidateCount={}",
                    TextUtil.preview(query, 60, ""), candidateVideoIds.size());
            return sqlFallback(query, category, tier, page, size);
        }
        List<String> rankedVideoIds = qualityRankedRecords.stream()
                .map(ReferenceVideoRecord::getVideoId)
                .filter(TextUtil::hasText)
                .toList();
        boolean hybridUsed = containsAny(rankedVideoIds, hybridVideoIds)
                || containsAny(rankedVideoIds, hybridEvidenceItemIds.keySet());
        Map<String, List<ReferenceVideoItemRecord>> evidenceByVideoId =
                loadRelevantEvidence(query, searchTexts, rankedVideoIds, hybridEvidenceItemIds);
        List<ReferenceVideoRecord> rankedRecords = qualityRankedRecords;
        boolean reranked = false;
        if (runtimeSettingService.isKnowledgeRerankEnabled() && qualityRankedRecords.size() > 1) {
            List<Integer> rerankOrder = knowledgeRerankClient.rerank(
                    query, toRerankTexts(qualityRankedRecords, matchedTopics, evidenceByVideoId));
            if (!rerankOrder.isEmpty()) {
                rankedRecords = reorderByIndices(qualityRankedRecords, rerankOrder);
                reranked = true;
            }
        }
        int offset = (page - 1) * size;
        List<ReferenceVideoResponse> cards = rankedRecords.stream()
                .skip(offset)
                .limit(size)
                .map(ReferenceVideoResponse::from)
                .toList();
        List<String> currentPageVideoIds = cards.stream()
                .map(ReferenceVideoResponse::videoId)
                .toList();
        List<ReferenceVideoMatchedTopic> visibleMatchedTopics = matchedTopics.stream()
                .filter(topic -> currentPageVideoIds.contains(topic.videoId()))
                .limit(MAX_TOPIC_CANDIDATES)
                .toList();
        List<ReferenceVideoEvidence> visibleEvidence = buildVisibleEvidence(currentPageVideoIds, evidenceByVideoId);
        boolean hasMore = page < MAX_PAGE && rankedRecords.size() > offset + cards.size();
        String mode = hybridUsed ? MODE_TOPIC_HYBRID : MODE_TOPIC_VECTOR;
        return new ReferenceVideoTopicSearchResponse(
                mode, strategy.name(), enhancedQueries, page, size, MAX_PAGE, hasMore,
                visibleMatchedTopics, visibleEvidence, cards, reranked);
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

    private List<String> hybridSearchMulti(List<String> searchTexts, String category, String tier, int topK) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String text : searchTexts) {
            merged.addAll(knowledgeHybridStore.hybridSearch(text, category, tier, topK));
        }
        return limitStrings(new ArrayList<>(merged), topK);
    }

    private Map<String, List<String>> childHybridSearchMulti(List<String> searchTexts,
                                                             String category,
                                                             String tier,
                                                             int topK) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String text : searchTexts) {
            for (KnowledgeHybridStore.HybridChildHit hit : knowledgeHybridStore.childHybridSearch(text, category, tier, topK)) {
                String videoId = hit.videoId();
                String itemId = hit.itemId();
                if (!TextUtil.hasText(videoId) || !TextUtil.hasText(itemId)) {
                    continue;
                }
                List<String> itemIds = result.computeIfAbsent(videoId, key -> new ArrayList<>());
                if (itemIds.size() < MAX_RELEVANT_EVIDENCE_PER_VIDEO && !itemIds.contains(itemId)) {
                    itemIds.add(itemId);
                }
            }
        }
        return result;
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
        List<String> keywords = extractSqlKeywords(query);
        List<ReferenceVideoRecord> records = knowledgeReferenceVideoMapper
                .searchByKeyword(category, tier, TextUtil.trimToNull(query), keywords, limit);
        List<ReferenceVideoResponse> cards = records.stream()
                .skip(offset)
                .limit(size)
                .map(ReferenceVideoResponse::from)
                .toList();
        boolean hasMore = page < MAX_PAGE && records.size() > offset + cards.size();
        return new ReferenceVideoTopicSearchResponse(
                MODE_SQL, QueryEnhanceStrategy.NONE.name(), List.of(), page, size, MAX_PAGE, hasMore,
                List.of(), List.of(), cards, false);
    }

    /**
     * SQL 兜底用关键词片段，而不是整串 query。
     * 用户常输入带空格、书名号、斜杠的标题；整串 LIKE 对这些符号过于敏感，会把父表真实存在的标题漏掉。
     */
    private List<String> extractSqlKeywords(String query) {
        String normalized = TextUtil.trimToNull(query);
        if (normalized == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Matcher matcher = SQL_KEYWORD_PATTERN.matcher(normalized);
        while (matcher.find() && result.size() < 6) {
            String token = matcher.group();
            if (token.length() < 2 || result.contains(token)) {
                continue;
            }
            result.add(token);
        }
        return result;
    }

    private List<String> toRerankTexts(List<ReferenceVideoRecord> records,
                                       List<ReferenceVideoMatchedTopic> matchedTopics,
                                       Map<String, List<ReferenceVideoItemRecord>> evidenceByVideoId) {
        Map<String, List<ReferenceVideoMatchedTopic>> topicsByVideoId = groupTopicsByVideoId(matchedTopics);
        return records.stream()
                .map(record -> buildRerankText(
                        record,
                        topicsByVideoId.getOrDefault(record.getVideoId(), List.of()),
                        evidenceByVideoId.getOrDefault(record.getVideoId(), List.of())))
                .toList();
    }

    /**
     * 在 top20 候选视频内部按 query 查相关评论 / 弹幕。
     * 子向量集合不可用或查询失败时，才退回 MySQL 代表证据；这样主排序优先看“和问题相关的观众反馈”，不是只看热门评论。
     */
    private Map<String, List<ReferenceVideoItemRecord>> loadRelevantEvidence(String query,
                                                                             List<String> searchTexts,
                                                                             List<String> videoIds,
                                                                             Map<String, List<String>> hybridEvidenceItemIds) {
        if (videoIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<ReferenceVideoItemRecord>> result = new LinkedHashMap<>(
                loadEvidenceByItemIds(filterItemIdsByVideoIds(hybridEvidenceItemIds, videoIds)));
        List<String> missingVideoIds = missingVideoIds(videoIds, result.keySet());
        if (knowledgeVectorStore.isChildReady()) {
            VectorStore childStore = knowledgeVectorStore.getChildVectorStore().orElse(null);
            if (childStore != null && !missingVideoIds.isEmpty()) {
                try {
                    Map<String, List<String>> itemIdsByVideoId =
                            searchRelevantEvidenceItemIds(childStore, searchTexts, missingVideoIds);
                    Map<String, List<ReferenceVideoItemRecord>> evidence = loadEvidenceByItemIds(itemIdsByVideoId);
                    if (!evidence.isEmpty()) {
                        result.putAll(evidence);
                        missingVideoIds = missingVideoIds(videoIds, result.keySet());
                    }
                } catch (Exception exception) {
                    log.warn("主题优先检索相关评论弹幕召回失败，退回代表证据。query={}", TextUtil.preview(query, 60, ""), exception);
                }
            }
        }
        if (!missingVideoIds.isEmpty()) {
            result.putAll(loadFallbackEvidence(missingVideoIds));
        }
        return result;
    }

    private Map<String, List<String>> filterItemIdsByVideoIds(Map<String, List<String>> itemIdsByVideoId,
                                                              List<String> videoIds) {
        if (itemIdsByVideoId == null || itemIdsByVideoId.isEmpty()) {
            return Map.of();
        }
        Set<String> allowedVideoIds = new HashSet<>(videoIds);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : itemIdsByVideoId.entrySet()) {
            if (allowedVideoIds.contains(entry.getKey()) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private List<String> missingVideoIds(List<String> orderedVideoIds, Set<String> existingVideoIds) {
        return orderedVideoIds.stream()
                .filter(videoId -> !existingVideoIds.contains(videoId))
                .toList();
    }

    private Map<String, List<String>> searchRelevantEvidenceItemIds(VectorStore childStore,
                                                                    List<String> searchTexts,
                                                                    List<String> videoIds) {
        Filter.Expression filter = buildEvidenceFilter(videoIds);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String text : searchTexts) {
            var builder = SearchRequest.builder()
                    .query(text)
                    .topK(MAX_RELEVANT_EVIDENCE_CANDIDATES);
            if (filter != null) {
                builder.filterExpression(filter);
            }
            List<Document> documents = childStore.similaritySearch(builder.build());
            if (documents == null) {
                continue;
            }
            for (Document document : documents) {
                String videoId = metadataText(document.getMetadata(), "videoId");
                String itemId = metadataText(document.getMetadata(), "itemId");
                if (!TextUtil.hasText(videoId) || !TextUtil.hasText(itemId)) {
                    continue;
                }
                List<String> itemIds = result.computeIfAbsent(videoId, key -> new ArrayList<>());
                if (itemIds.size() < MAX_RELEVANT_EVIDENCE_PER_VIDEO && !itemIds.contains(itemId)) {
                    itemIds.add(itemId);
                }
            }
        }
        return result;
    }

    private Filter.Expression buildEvidenceFilter(List<String> videoIds) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        // 候选视频已经由 MySQL 按 category/tier 二次过滤过；这里只按 videoId 限定，避免向量 metadata 滞后导致漏掉证据。
        return builder.in("videoId", videoIds.stream().map(value -> (Object) value).toList()).build();
    }

    private Map<String, List<ReferenceVideoItemRecord>> loadEvidenceByItemIds(Map<String, List<String>> itemIdsByVideoId) {
        List<String> allItemIds = new ArrayList<>();
        for (List<String> itemIds : itemIdsByVideoId.values()) {
            allItemIds.addAll(itemIds);
        }
        if (allItemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, ReferenceVideoItemRecord> byItemId = new LinkedHashMap<>();
        for (ReferenceVideoItemRecord item : knowledgeReferenceVideoMapper.listItemsByItemIds(allItemIds)) {
            byItemId.put(item.getItemId(), item);
        }
        Map<String, List<ReferenceVideoItemRecord>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : itemIdsByVideoId.entrySet()) {
            List<ReferenceVideoItemRecord> items = new ArrayList<>();
            for (String itemId : entry.getValue()) {
                ReferenceVideoItemRecord item = byItemId.get(itemId);
                if (item != null) {
                    items.add(item);
                }
            }
            if (!items.isEmpty()) {
                result.put(entry.getKey(), items);
            }
        }
        return result;
    }

    private Map<String, List<ReferenceVideoItemRecord>> loadFallbackEvidence(List<String> videoIds) {
        if (videoIds.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, List<ReferenceVideoItemRecord>> result = new LinkedHashMap<>();
            for (ReferenceVideoItemRecord item : knowledgeReferenceVideoMapper
                    .listTopEvidenceItemsByVideoIds(videoIds, MAX_FALLBACK_EVIDENCE_PER_SOURCE)) {
                if (TextUtil.hasText(item.getVideoId())) {
                    result.computeIfAbsent(item.getVideoId(), key -> new ArrayList<>()).add(item);
                }
            }
            return result;
        } catch (Exception exception) {
            log.warn("主题优先检索加载代表评论弹幕失败，退化为卡片与主题排序。videoCount={}", videoIds.size(), exception);
            return Map.of();
        }
    }

    private List<String> mergeCandidateVideoIds(List<String> topicVideoIds,
                                                List<String> hybridVideoIds,
                                                Set<String> hybridEvidenceVideoIds,
                                                int limit) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.addAll(topicVideoIds);
        merged.addAll(hybridVideoIds);
        merged.addAll(hybridEvidenceVideoIds);
        return limitStrings(new ArrayList<>(merged), limit);
    }

    private boolean containsAny(List<String> values, Iterable<String> candidates) {
        if (values.isEmpty()) {
            return false;
        }
        Set<String> valueSet = new HashSet<>(values);
        for (String candidate : candidates) {
            if (valueSet.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private List<String> limitStrings(List<String> values, int limit) {
        if (values.size() <= limit) {
            return values;
        }
        return new ArrayList<>(values.subList(0, limit));
    }

    private List<ReferenceVideoEvidence> buildVisibleEvidence(List<String> currentPageVideoIds,
                                                              Map<String, List<ReferenceVideoItemRecord>> evidenceByVideoId) {
        List<ReferenceVideoEvidence> result = new ArrayList<>();
        for (String videoId : currentPageVideoIds) {
            List<ReferenceVideoItemRecord> records = evidenceByVideoId.get(videoId);
            if (records == null || records.isEmpty()) {
                continue;
            }
            result.add(new ReferenceVideoEvidence(videoId, records.stream().map(this::toEvidenceItem).toList()));
        }
        return result;
    }

    private Map<String, List<ReferenceVideoMatchedTopic>> groupTopicsByVideoId(
            List<ReferenceVideoMatchedTopic> matchedTopics) {
        Map<String, List<ReferenceVideoMatchedTopic>> result = new LinkedHashMap<>();
        for (ReferenceVideoMatchedTopic topic : matchedTopics) {
            if (!TextUtil.hasText(topic.videoId())) {
                continue;
            }
            result.computeIfAbsent(topic.videoId(), key -> new ArrayList<>()).add(topic);
        }
        return result;
    }

    private String buildRerankText(ReferenceVideoRecord record,
                                   List<ReferenceVideoMatchedTopic> topics,
                                   List<ReferenceVideoItemRecord> evidenceItems) {
        StringBuilder builder = new StringBuilder();
        builder.append("标题：").append(TextUtil.trimToDefault(record.getTitle(), "")).append('\n');
        if (TextUtil.hasText(record.getCategory())) {
            builder.append("分区：").append(record.getCategory()).append('\n');
        }
        if (TextUtil.hasText(record.getHighlightSummary())) {
            builder.append("亮点摘要：")
                    .append(TextUtil.abbreviateWithSuffix(record.getHighlightSummary(), RERANK_CARD_MAX_CHARS, "..."))
                    .append('\n');
        }
        if (TextUtil.hasText(record.getDescription())) {
            builder.append("简介：")
                    .append(TextUtil.abbreviateWithSuffix(record.getDescription(), RERANK_CARD_MAX_CHARS, "..."))
                    .append('\n');
        }
        if (TextUtil.hasText(record.getTags())) {
            builder.append("标签：").append(TextUtil.abbreviateWithSuffix(record.getTags(), 240, "...")).append('\n');
        }
        if (!topics.isEmpty()) {
            builder.append("命中主题：").append('\n');
            for (ReferenceVideoMatchedTopic topic : topics) {
                builder.append("- ")
                        .append(TextUtil.trimToDefault(topic.chunkType(), "主题"))
                        .append(" / ")
                        .append(TextUtil.trimToDefault(topic.chunkTitle(), "未命名"))
                        .append("：")
                        .append(TextUtil.abbreviateWithSuffix(
                                TextUtil.trimToDefault(topic.preview(), ""), RERANK_TOPIC_MAX_CHARS, "..."))
                        .append('\n');
            }
        }
        if (!evidenceItems.isEmpty()) {
            builder.append("相关评论弹幕：").append('\n');
            for (ReferenceVideoItemRecord item : evidenceItems) {
                builder.append("- ")
                        .append(sourceTypeLabel(item.getSourceType()))
                        .append(" / ")
                        .append(sentimentLabel(item.getSentiment()))
                        .append("：")
                        .append(TextUtil.abbreviateWithSuffix(
                                TextUtil.trimToDefault(item.getContent(), ""), RERANK_EVIDENCE_MAX_CHARS, "..."));
                if (item.getLikeCount() != null) {
                    builder.append("（点赞 ").append(item.getLikeCount()).append("）");
                }
                builder.append('\n');
            }
        }
        return builder.toString().trim();
    }

    private String sourceTypeLabel(String sourceType) {
        return "DANMAKU".equals(sourceType) ? "弹幕" : "评论";
    }

    private String sentimentLabel(String sentiment) {
        if ("POSITIVE".equals(sentiment)) {
            return "正向";
        }
        if ("NEGATIVE".equals(sentiment)) {
            return "负向";
        }
        return TextUtil.trimToDefault(sentiment, "中性");
    }

    /**
     * rerank 返回的是候选数组下标。只按合法下标重排；如果上游返回重复下标，这里按第一次出现保留，避免重复卡片。
     */
    private List<ReferenceVideoRecord> reorderByIndices(List<ReferenceVideoRecord> candidates, List<Integer> order) {
        List<ReferenceVideoRecord> result = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (Integer index : order) {
            if (index != null && index >= 0 && index < candidates.size() && used.add(index)) {
                result.add(candidates.get(index));
            }
        }
        if (result.size() < candidates.size()) {
            for (int i = 0; i < candidates.size(); i++) {
                if (used.add(i)) {
                    result.add(candidates.get(i));
                }
            }
        }
        return result;
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
