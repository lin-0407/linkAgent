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
import com.link.linkagent.llm.usage.LlmUsageContext;
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
 * 主题优先案例检索服务（阶段 5.2c 的主题分支）。
 * <p>
 * 在知识库 RAG 架构中的位置：<b>检索层的主题优先变体</b>——与 {@link KnowledgeReferenceRetrievalService}
 * 并列，专注于”先找主题、再定候选、按质量排序”的查询路径。适合用户模糊搜索（如”有什么好的开场白案例”）、
 * 浏览性探索（如”看看知识区高互动视频怎么做弹幕引导”）等场景。
 * <p>
 * 核心设计——为什么需要独立的主题优先链路：
 * <ol>
 *   <li><b>避免向量相似度的粒度错配</b>：父视频大块向量检索会把”视频整体的文本相似”误当成
 *       “创作者真正关心的某个具体主题”。例如用户问”怎么做转场”，父检索可能返回一个标题、
 *       简介、标签都和”转场”无关但整体语义相近的视频，而该视频的 10 个主题段落中有 1 个确实聊了转场——
 *       中块（主题段落）检索能精准命中这个段落，然后上卷回父视频。</li>
 *   <li><b>质量分作为排序主导</b>：视频候选先通过主题相关性召回，再按 quality_score 排序，
 *       而非纯向量相似度。这样既保证了候选与 query 主题相关，又优先展示”该主题下做得好”的视频，
 *       而非”标题最像 query 但互动数据很差的视频”。</li>
 *   <li><b>页面化的浏览体验</b>：支持分页（page/size），配合 MAX_PAGE=4 上限防止深度翻页请求撑爆
 *       数据库和向量库。前 20 条候选（MAX_TOPIC_CANDIDATES）足够覆盖 4 页 * 5 条/页的容量。</li>
 * </ol>
 */
@Service
public class KnowledgeReferenceTopicSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceTopicSearchService.class);

    // ======================== 分页参数 ========================

    /** 默认当前页：第 1 页。 */
    private static final int DEFAULT_PAGE = 1;
    /** 默认每页大小：5 条。 */
    private static final int DEFAULT_SIZE = 5;
    /** 最大允许页码：深度翻页会撑大 MySQL OFFSET + 向量检索 topK 成本，4 页足够覆盖前 20 条候选。 */
    private static final int MAX_PAGE = 4;
    /** 每页最大条数：经验值——主题优先展示每条卡片需要占用较多垂直空间（标题+主题预览+证据），5 条/页可读性最佳。 */
    private static final int MAX_SIZE = 5;

    // ======================== 候选与召回数量上限 ========================

    /** 主题候选视频数上限：中块/ hybrid 召回后合并去重取前 20 条，送入质量分排序和重排。 */
    private static final int MAX_TOPIC_CANDIDATES = 20;
    /** 中块文档检索候选数：中块粒度细、一个视频可能命中多个 chunk，宽召回再按 videoId 上卷。3 倍乘数平衡召回广度和向量检索延迟。 */
    private static final int MAX_TOPIC_DOCUMENT_CANDIDATES = MAX_TOPIC_CANDIDATES * 3;
    /** 分析上下文接口单次查询的证据条目上限：避免某视频弹幕数万条时一次性加载撑爆内存和响应大小。 */
    private static final int MAX_CONTEXT_EVIDENCE_ITEMS = 30;
    /** 每视频最多展示的相关证据条数（主题优先链路）：比标准检索的 3 条略高，因为主题浏览场景用户更想”看例子”。 */
    private static final int MAX_RELEVANT_EVIDENCE_PER_VIDEO = 4;
    /** 相关证据检索的候选文档数：宽召回后按 videoId 聚合 + 截取，2x 乘数保证足够的子证据覆盖。 */
    private static final int MAX_RELEVANT_EVIDENCE_CANDIDATES = MAX_TOPIC_CANDIDATES * MAX_RELEVANT_EVIDENCE_PER_VIDEO * 2;
    /** 代表证据兜底每类来源的上限：当子向量库不可用时，从 MySQL 按点赞数取最热门的评论/弹幕做兜底展示。 */
    private static final int MAX_FALLBACK_EVIDENCE_PER_SOURCE = 2;

    // ======================== 文本截断参数 ========================

    /** 主题预览截断字符数：240 字足够展示 3-4 行的段落核心内容，前端卡片不会因为过长预览变形。 */
    private static final int TOPIC_PREVIEW_MAX_CHARS = 240;
    /** 送重排时的主题文本截断：720 字覆盖 2-3 个命中主题的完整预览，给精排模型足够的语义上下文。 */
    private static final int RERANK_TOPIC_MAX_CHARS = 720;
    /** 送重排时的案例卡片文本截断：900 字涵盖标题+分区+亮点摘要+标签，与 qwen3-rerank 4000 token 限制留有充足余量。 */
    private static final int RERANK_CARD_MAX_CHARS = 900;
    /** 送重排时的单条证据文本截断：260 字取评论/弹幕的核心观点，去掉尾部长篇重复或无关内容。 */
    private static final int RERANK_EVIDENCE_MAX_CHARS = 260;

    // ======================== 模式常量 ========================

    /** 主题优先 + 纯向量检索（dense only，无 hybrid 混合）。 */
    private static final String MODE_TOPIC_VECTOR = "TOPIC_VECTOR";
    /** 主题优先 + hybrid 混合检索（dense+BM25+RRF）。 */
    private static final String MODE_TOPIC_HYBRID = "TOPIC_HYBRID";
    /** RAG 不可用时的纯 SQL 关键词兜底。 */
    private static final String MODE_SQL = "SQL";

    /** SQL 关键词切词正则：匹配连续的中文汉字、英文字母、数字。 */
    private static final Pattern SQL_KEYWORD_PATTERN = Pattern.compile("[\\p{IsHan}A-Za-z0-9]+");

    /** 允许的案例层级过滤白名单。 */
    private static final Set<String> ALLOWED_TIERS = Set.of("BENCHMARK", "COMPETITOR", "OWN_HISTORY");

    private final KnowledgeRagProperties knowledgeRagProperties;
    /** 知识库专用向量库持有者（与 RetrievalService 共用同一个持有者，避免 Bean 冲突）。 */
    private final KnowledgeVectorStore knowledgeVectorStore;
    /** 知识引用视频表的 Mapper。 */
    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;
    /** 原生 hybrid 存储（5.2d）：主题优先链路也支持 hybrid 补召回。 */
    private final KnowledgeHybridStore knowledgeHybridStore;
    /** 查询增强器（5.2b）。 */
    private final KnowledgeQueryEnhancer knowledgeQueryEnhancer;
    /** 重排客户端（5.2e）。 */
    private final KnowledgeRerankClient knowledgeRerankClient;
    /** 运行期设置服务：rerank 是否启用由设置页动态控制。 */
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
     * 主题优先检索主入口。
     * <p>
     * 检索流程（分 5 个阶段）：
     * <ol>
     *   <li><b>查询增强（5.2b）</b>：按策略扩展为 1-N 条检索文本</li>
     *   <li><b>主题中块召回</b>：查中块向量集合，找到与 query 语义相关的视频主题段落，按 videoId 上卷</li>
     *   <li><b>hybrid 补召回</b>：hybrid 开启时，用 dense+BM25+RRF 对父/子集合做补充召回，
     *       弥补纯中块召回可能的遗漏——中块是细粒度语义精确匹配，hybrid 补召回覆盖更泛化的语义</li>
     *   <li><b>质量分排序</b>：合并候选 videoId，回查 MySQL 父表，按 quality_score 降序排列</li>
     *   <li><b>Rerank 精排（可选）</b>：用原始 query 对候选重排，重排输入是卡片信息 + 命中主题 + 相关证据</li>
     * </ol>
     * <p>
     * 分页处理：前 4 步产出最多 20 条全量候选，第 5 步重排后按 page/size 截取当前页数据。
     * 命中主题和相关证据只展示当前页的——避免前端侧拿到不可见卡片的数据。
     *
     * @param request 主题检索请求（query 必填，category/tier/page/size/strategy 可选）
     * @return 主题检索响应，含检索模式、命中主题列表、证据片段、分页信息、案例卡片列表
     */
    public ReferenceVideoTopicSearchResponse topicSearch(ReferenceVideoTopicSearchRequest request) {
        String query = request.query().trim();
        String category = TextUtil.trimToNull(request.category());
        String tier = normalizeTier(request.tier());
        int page = resolvePage(request.page());
        int size = resolveSize(request.size());
        QueryEnhanceStrategy strategy = resolveStrategy(request.strategy());

        // 能力检测：分三层（RAG 总开关 → 中块就绪 → hybrid 就绪），任一链路可用就继续，全不可用走 SQL 兜底。
        boolean ragEnabled = knowledgeRagProperties.isEnabled();
        boolean chunkEnabled = ragEnabled && knowledgeVectorStore.isChunkReady();
        boolean hybridEnabled = ragEnabled
                && knowledgeRagProperties.getHybrid().isEnabled()
                && knowledgeHybridStore.isReady();
        // 中块和 hybrid 都不可用：无向量检索能力，直接走 SQL 质量分兜底。
        // 设计意图——主题优先场景的 SQL 兜底不同于标准检索：它走 MySQL 的 quality_score 排序，
        // 因为主题优先链路本身就是"按质量分排序"的浏览性查询，和 SQL 兜底的排序逻辑天然一致。
        if (!chunkEnabled && !hybridEnabled) {
            return sqlFallback(query, category, tier, page, size);
        }
        VectorStore chunkStore = chunkEnabled ? knowledgeVectorStore.getChunkVectorStore().orElse(null) : null;
        // 中块 store 实际为空（声称就绪但底层拿不到）且 hybrid 也不可用：二次兜底
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

        // 阶段 1：主题中块召回——查中块向量集合，找到与 query 语义相关的主题段落。
        // 中块异常的策略：
        // - hybrid 未就绪：无法补召回，直接退回 SQL 质量分兜底
        // - hybrid 已就绪：中块异常不应丢掉 BM25+dense 的补召回能力，继续往下走 hybrid 路径
        List<ReferenceVideoMatchedTopic> matchedTopics = List.of();
        if (chunkStore != null) {
            try {
                matchedTopics = searchMatchedTopics(chunkStore, searchTexts, category, tier, MAX_TOPIC_DOCUMENT_CANDIDATES);
            } catch (Exception exception) {
                if (!hybridEnabled) {
                    log.warn("主题优先检索中块召回失败，退回 SQL 质量分兜底。query={}", TextUtil.preview(query, 60, ""), exception);
                    return sqlFallback(query, category, tier, page, size);
                }
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
            // 向量库正常但零命中——可能是用户输入了标题关键词、BV 号或纯强关键词，
            // 这些信息在中块语义向量中可能找不到匹配（中块索引的是主题段落文本，而非标题/BV）。
            // SQL 关键词兜底能覆盖这种场景，同时也暴露了”中块索引未覆盖此查询”的真实状态（日志可见）。
            log.info("主题优先检索中块/hybrid 均零命中，退回 SQL 质量分兜底。query={}", TextUtil.preview(query, 60, ""));
            return sqlFallback(query, category, tier, page, size);
        }
        // 回查 MySQL 父表：按 quality_score 降序，而非 IN 列表的自然顺序。
        // 主题优先的核心差异——候选确定后，排序由质量分主导而非向量相似度。
        List<ReferenceVideoRecord> qualityRankedRecords = knowledgeReferenceVideoMapper
                .listByVideoIdsOrderByQuality(candidateVideoIds, category, tier, 0, MAX_TOPIC_CANDIDATES);
        if (qualityRankedRecords.isEmpty()) {
            // 中块 metadata 中的 videoId 可能因索引延迟而指向 MySQL 中不存在的记录
            // （如视频已被软删但向量库未同步），退回 SQL 兜底避免前端空屏
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
     * 点击视频卡片后加载该视频的完整分析上下文（主题段落 + 评论弹幕证据）。
     * <p>
     * 设计意图：直接读 MySQL 事实源，不要求 RAG 开启——用户点击卡片查看详情是最基本的功能需求，
     * 不应因为 RAG 开关关闭就看不到分析数据。这些是已经存储在 MySQL 中的结构化数据，
     * 不依赖 Milvus 向量检索。
     *
     * @param videoId 视频 ID（必填）
     * @return 分析上下文：视频信息 + 主题段落列表 + 评论/弹幕证据片段
     * @throws ResponseStatusException 400 videoId 为空 / 404 未找到对应视频
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

    /**
     * 在主题中块向量集合中做多路语义检索，返回去重后的命中主题列表。
     * <p>
     * 使用 LinkedHashMap 按 chunkId 去重并保持首次命中顺序——首次命中来自排在最前面的检索文本
     * （通常是原始 query 或最接近原始 query 的改写），它的相似度顺序更有语义价值。
     * putIfAbsent 而非直接 put 确保同一个 chunk 只在首次命中时写入。
     *
     * @param chunkStore 中块向量库（Milvus 中块集合对应的 VectorStore）
     * @param searchTexts 1-N 条检索文本
     * @param category 分区过滤
     * @param tier 案例层级过滤
     * @param topK 顶层检索候选数
     * @return 去重后的命中主题列表
     */
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
            List<Document> documents;
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库主题优先中块检索")) {
                documents = chunkStore.similaritySearch(builder.build());
            }
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
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库主题优先 hybrid 父检索")) {
                merged.addAll(knowledgeHybridStore.hybridSearch(text, category, tier, topK));
            }
        }
        return limitStrings(new ArrayList<>(merged), topK);
    }

    private Map<String, List<String>> childHybridSearchMulti(List<String> searchTexts,
                                                             String category,
                                                             String tier,
                                                             int topK) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String text : searchTexts) {
            List<KnowledgeHybridStore.HybridChildHit> hits;
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库主题优先 hybrid 子检索")) {
                hits = knowledgeHybridStore.childHybridSearch(text, category, tier, topK);
            }
            for (KnowledgeHybridStore.HybridChildHit hit : hits) {
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
     * 从查询文本中通过正则切出最多 6 个独立关键词，用于 SQL 多词 LIKE 搜索。
     * <p>
     * 切词策略：匹配连续的中文汉字、英文字母、数字——这些是最有检索区分度的语义单元。
     * 过滤条件：长度 < 2 的单字/单字母区分度太低（容易返回大量噪音），已出现的词去重。
     * 上限 6 个——防止超长查询切出几十个词导致 SQL 膨胀。
     *
     * @param query 原始查询文本（可能为 null）
     * @return 最多 6 个去重关键词列表
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
     * 加载与查询相关的评论/弹幕证据（三级降级策略）。
     * <p>
     * 三级策略按优先级递减，后一级仅在前一级未覆盖全部 videoId 时触发：
     * <ol>
     *   <li><b>hybrid 证据</b>：优先使用 hybrid 子召回产出的 itemId（dense+BM25+RRF 质量最高）</li>
     *   <li><b>向量语义证据</b>：对未覆盖的 videoId，用 dense 语义检索在子表集合中搜索相关片段</li>
     *   <li><b>代表证据兜底</b>：对仍未覆盖的 videoId，从 MySQL 按点赞数取最热门的评论/弹幕做兜底</li>
     * </ol>
     * <p>
     * 设计权衡：主排序优先看”和问题语义相关的观众反馈”，而不是只看热门评论。
     * 如果子向量库不可用或查询失败，降级到 MySQL 代表证据（按点赞数排序）作为兜底，
     * 确保即使 Milvus 出问题，用户仍能看到一些相关评论。
     * <p>
     * 每视频最多 {@link #MAX_RELEVANT_EVIDENCE_PER_VIDEO} 条证据——防止单视频证据过多撑大响应。
     *
     * @param query 原始查询（用于日志）
     * @param searchTexts 增强后的检索文本列表
     * @param videoIds 需要加载证据的候选视频 ID 列表
     * @param hybridEvidenceItemIds hybrid 子召回产出的 videoId → itemId 映射
     * @return videoId → 证据记录列表的映射
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
            List<Document> documents;
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库主题优先相关证据检索")) {
                documents = childStore.similaritySearch(builder.build());
            }
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

    /**
     * 构建证据检索的 Milvus 过滤表达式——仅在指定 videoId 范围内搜索。
     * <p>
     * 为何只用 videoId 过滤而不加 category/tier：候选视频已经由 MySQL 按 category/tier 做了二次过滤，
     * 如果在向量检索时再加 category/tier 过滤，可能因为向量 metadata 滞后（元数据未同步更新 category）
     * 导致本来属于正确分类的证据片段被错误滤掉。
     *
     * @param videoIds 候选视频 ID 列表
     * @return Milvus IN 过滤表达式
     */
    private Filter.Expression buildEvidenceFilter(List<String> videoIds) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
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

    /**
     * 加载代表证据兜底——从 MySQL 按点赞数取最热门的评论/弹幕。
     * <p>
     * 这是三级降级策略中的最后一级：当 hybrid 证据和向量语义证据都不可用时，
     * 用热门评论/弹幕作为代表证据。展示给用户的不是"和查询语义最相关的"（无法做语义匹配），
     * 而是"该视频下最多人赞同的"（至少是观众共鸣最强的反馈）。
     * <p>
     * 异常被静默捕获——代表证据加载失败只意味着页面没有证据展示，不应中断主流程的卡片+主题展示。
     *
     * @param videoIds 需要加载代表证据的 videoId 列表
     * @return videoId → 证据记录列表的映射
     */
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

    /**
     * 拼装主题优先链路送 rerank 的卡片语义文本。
     * <p>
     * 与标准检索的 rerank 文本（见 ）相比，
     * 主题优先多拼了「命中主题」和「相关评论弹幕」两部分信息。原因：主题优先链路的候选来源是中块匹配，
     * 精排模型需要知道"这张卡片命中了哪些主题段落"和"这些评论弹幕说了什么"才能做出比纯卡片信息更精准的判断。
     * <p>
     * 三段信息各有截断限制：话题预览截到 720 字、卡片文本截到 900 字、单条证据截到 260 字。
     *
     * @param record 案例卡片记录
     * @param topics 该卡片命中的主题段落列表
     * @param evidenceItems 该卡片相关的评论/弹幕证据
     * @return 拼装后的语义文本
     */
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

    /**
     * 将证据来源类型编码转为中文展示标签。
     * B 站视频的子条目类型主要是 DANMAKU（弹幕）和 COMMENT（评论），
     * 其他未知类型回退显示"评论"（因评论比弹幕更通用）。
     */
    private String sourceTypeLabel(String sourceType) {
        return "DANMAKU".equals(sourceType) ? "弹幕" : "评论";
    }

    /**
     * 将情感倾向编码转为中文展示标签。
     * POSITIVE/NEGATIVE 为标准枚举值；其他值回退为原值或"中性"——防御未来新增情感标签（如 MIXED）。
     */
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
     * 按 rerank 返回的下标顺序重排候选列表，未在序中出现的候选追加到末尾。
     * <p>
     * 与 {@link KnowledgeReferenceRetrievalService} 中同名方法的区别：
     * 主题优先链路在 rerank 只返回部分下标时（rerank 可能因 top_n 截断只返回 top 10），
     * 将未被重排的候选追加到末尾保留，而非丢弃——主题浏览场景下用户可能翻到第 2-3 页，
     * 被重排截断的候选仍有展示价值。
     * <p>
     * 如果上游返回重复下标，按第一次出现保留（used.add 防重复），避免同一卡片出现两次。
     *
     * @param candidates 质量分排序后的候选卡片列表
     * @param order rerank 返回的下标顺序
     * @return 重排后的完整候选列表（不丢任何候选）
     */
    private List<ReferenceVideoRecord> reorderByIndices(List<ReferenceVideoRecord> candidates, List<Integer> order) {
        List<ReferenceVideoRecord> result = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (Integer index : order) {
            if (index != null && index >= 0 && index < candidates.size() && used.add(index)) {
                result.add(candidates.get(index));
            }
        }
        // 未被重排覆盖的候选追加到末尾——主题优先场景不丢候选，只调整顺序
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
