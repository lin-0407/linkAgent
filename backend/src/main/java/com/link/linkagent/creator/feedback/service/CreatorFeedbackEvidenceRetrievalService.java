package com.link.linkagent.creator.feedback.service;

import com.link.linkagent.creator.feedback.config.CreatorFeedbackRagProperties;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackEvidenceRetrievalResult;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackItemRecord;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 反馈追问证据检索服务（双层检索 + 自动降级）。
 * <p>
 * 采用”双层证据检索”架构：
 * <ol>
 *   <li><b>向量检索（优先）</b>：RAG 启用且 Milvus 可用时，将用户追问做语义相似度搜索，
 *       召回的 itemId 必须用 taskId + is_deleted = 0 回查 MySQL 验证——Milvus 只负责”找可能相关的
 *       证据 itemId”，MySQL 仍然是当前任务评论弹幕明细的事实来源。</li>
 *   <li><b>SQL 轻量匹配（兜底）</b>：基于关键词命中 + 问题意图 + 社交信号（点赞/回复数）
 *       的评分算法，选出最相关的证据。</li>
 * </ol>
 * <p>
 * 降级策略：
 * <ul>
 *   <li>Milvus 不可用 → 全程走 SQL</li>
 *   <li>Milvus 命中不足（低于 minVectorHitCount）→ 向量证据在前，SQL 证据补充在后，按 itemId 去重</li>
 *   <li>向量检索运行期异常（连接、维度、超时）→ 记录日志后回退 SQL，不中断追问</li>
 * </ul>
 * <p>
 * 架构决策：本服务承接了原先写在 CreatorFeedbackService 里的 SQL 证据评分逻辑。
 * “选证据”是检索职责而非编排职责，搬到这里能让 CreatorFeedbackService.chat 退化为编排者，
 * 也避免”检索服务需要回调 CreatorFeedbackService 来获取明细”造成的循环依赖。
 */
@Service
public class CreatorFeedbackEvidenceRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(CreatorFeedbackEvidenceRetrievalService.class);

    /** 反馈 RAG 配置属性（topK、minVectorHitCount 等检索参数） */
    private final CreatorFeedbackRagProperties ragProperties;
    /** 反馈明细数据访问层（用于回查 MySQL 验证向量召回的 itemId） */
    private final CreatorFeedbackMapper creatorFeedbackMapper;
    /**
     * 可选 VectorStore Bean。
     * 默认 VECTOR_STORE_TYPE=none 时没有这个 Bean，用 ObjectProvider 而非直接注入——
     * 这样演示环境不需要 Milvus 也能启动应用，全程走 SQL 检索。
     */
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    /** 运行期设置服务，控制 RAG 业务开关 */
    private final RuntimeSettingService runtimeSettingService;

    public CreatorFeedbackEvidenceRetrievalService(CreatorFeedbackRagProperties ragProperties,
                                                   CreatorFeedbackMapper creatorFeedbackMapper,
                                                   ObjectProvider<VectorStore> vectorStoreProvider,
                                                   RuntimeSettingService runtimeSettingService) {
        this.ragProperties = ragProperties;
        this.creatorFeedbackMapper = creatorFeedbackMapper;
        this.vectorStoreProvider = vectorStoreProvider;
        this.runtimeSettingService = runtimeSettingService;
    }

    /**
     * 选出本次追问可引用的证据（双层检索入口）。
     * <p>
     * 检索决策树：
     * <pre>
     * RAG 开关关闭 or VectorStore 不可用 → SQL 轻量匹配
     * VectorStore 可用 → 向量检索
     *   ├─ 命中数 ≥ minVectorHitCount → 纯向量结果（MODE_VECTOR）
     *   ├─ 命中不足 → 向量 + SQL 混合（MODE_VECTOR_WITH_SQL_FALLBACK），按 itemId 去重
     *   └─ 异常 → 回退 SQL（MODE_SQL）
     * </pre>
     * <p>
     * items 参数由调用方（CreatorFeedbackService.chat）传入，避免本服务重复查库。
     * 向量检索成功后仍需用 taskId 回查 MySQL——这是核心安全机制：
     * 向量库可能残留旧导入批次的文档或脏数据，只有 MySQL 的 taskId + is_deleted = 0
     * 才能保证返回的证据属于当前任务且未被软删除。
     *
     * @param taskId   当前任务（已由上层确认存在）
     * @param question 用户追问
     * @param items    当前任务全部有效明细，用于 SQL 兜底评分（避免重复查库）
     * @return 检索结果（含证据列表、检索模式、RAG 启用状态）
     */
    public CreatorFeedbackEvidenceRetrievalResult retrieve(String taskId,
                                                           String question,
                                                           List<CreatorFeedbackItemRecord> items) {
        boolean ragEnabled = runtimeSettingService.isCreatorFeedbackRagEnabled();
        // 只有业务开关打开时才尝试取 VectorStore；关着时连基础设施都不碰，保证默认演示路径零成本。
        VectorStore vectorStore = ragEnabled ? vectorStoreProvider.getIfAvailable() : null;
        if (vectorStore == null) {
            return new CreatorFeedbackEvidenceRetrievalResult(
                    selectSqlEvidence(question, items),
                    CreatorFeedbackEvidenceRetrievalResult.MODE_SQL,
                    ragEnabled
            );
        }

        List<CreatorFeedbackItemRecord> vectorEvidence;
        try {
            vectorEvidence = retrieveByVector(taskId, question, vectorStore);
        } catch (Exception exception) {
            // 向量检索的连接、维度、超时等运行期异常不应中断追问；记录后回退 SQL 证据，让用户仍能拿到回答。
            log.warn("向量检索失败，回退 SQL 证据。taskId={}", taskId, exception);
            return new CreatorFeedbackEvidenceRetrievalResult(
                    selectSqlEvidence(question, items),
                    CreatorFeedbackEvidenceRetrievalResult.MODE_SQL,
                    ragEnabled
            );
        }

        int limit = Math.max(1, ragProperties.getTopK());
        int minHit = Math.max(1, ragProperties.getMinVectorHitCount());
        if (vectorEvidence.size() >= minHit) {
            return new CreatorFeedbackEvidenceRetrievalResult(
                    limit(vectorEvidence, limit),
                    CreatorFeedbackEvidenceRetrievalResult.MODE_VECTOR,
                    true
            );
        }

        // 命中不足：用 SQL 证据补足，向量证据排在前面（语义更相关），SQL 证据补后面，按 itemId 去重。
        List<CreatorFeedbackItemRecord> sqlEvidence = selectSqlEvidence(question, items);
        List<CreatorFeedbackItemRecord> merged = mergeDistinctById(vectorEvidence, sqlEvidence, limit);
        return new CreatorFeedbackEvidenceRetrievalResult(
                merged,
                CreatorFeedbackEvidenceRetrievalResult.MODE_VECTOR_WITH_SQL_FALLBACK,
                true
        );
    }

    // ============================ 向量检索 + MySQL 回查 ============================
    // 向量检索链路：question → Milvus similaritySearch → 取 itemId → MySQL 回查验证 → 按向量顺序重排

    /**
     * 向量检索 + MySQL 回查验证。
     * <p>
     * 完整链路：
     * <ol>
     *   <li>构建 Milvus SearchRequest（含 taskId 过滤 + topK 限制）</li>
     *   <li>执行相似度搜索，返回 Document 列表</li>
     *   <li>从 Document 中提取 itemId（优先读 metadata.itemId，回退 document.getId）</li>
     *   <li>用 taskId + itemIds 回查 MySQL，只取 is_deleted=0 且非噪声的记录</li>
     *   <li>按向量相似度顺序重排回查结果（MySQL IN 查询不保证顺序）</li>
     * </ol>
     * <p>
     * 关键安全机制：filterExpression 用 Spring AI FilterExpressionBuilder 构建而非字符串拼接——
     * 避免 taskId 中的特殊字符破坏 Milvus 表达式语法。
     * <p>
     * 噪声明细过滤：即使被索引过也不作为证据，保持和 SQL 证据一致的质量口径。
     * 用户问"观众有什么问题"时收到一堆"哈哈"、"666"是无意义的。
     *
     * @param taskId 创作任务 ID
     * @param question 用户追问
     * @param vectorStore Milvus VectorStore
     * @return 按相似度排序的有效证据列表（可能为空）
     */
    private List<CreatorFeedbackItemRecord> retrieveByVector(String taskId, String question, VectorStore vectorStore) {
        // 过滤表达式用 Spring AI 的 FilterExpressionBuilder 构建，不手工拼接字符串，避免 taskId 里的特殊字符破坏表达式。
        Filter.Expression taskFilter = new FilterExpressionBuilder().eq("taskId", taskId).build();
        SearchRequest searchRequest = SearchRequest.builder()
                .query(TextUtil.trimToDefault(question, ""))
                .topK(Math.max(1, ragProperties.getTopK()))
                .filterExpression(taskFilter)
                .build();
        List<Document> documents = vectorStore.similaritySearch(searchRequest);
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        // 保留向量相似度顺序的 itemId 列表（distinct 防止同一条被多文档命中）。
        List<String> orderedItemIds = documents.stream()
                .map(this::extractItemId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (orderedItemIds.isEmpty()) {
            return List.of();
        }

        // 事实来源回查：只认 MySQL 当前有效明细，向量库返回的旧文档自然被 taskId + is_deleted = 0 过滤掉。
        List<CreatorFeedbackItemRecord> records = creatorFeedbackMapper.listItemsByTaskIdAndItemIds(taskId, orderedItemIds);
        Map<String, CreatorFeedbackItemRecord> recordByItemId = new LinkedHashMap<>();
        for (CreatorFeedbackItemRecord record : records) {
            // 噪声明细即使被索引过也不作为证据，保持和 SQL 证据一致的质量口径。
            if (Boolean.TRUE.equals(record.getNoise())) {
                continue;
            }
            recordByItemId.put(record.getItemId(), record);
        }

        // 按向量相似度顺序重排回查结果（MySQL IN 查询不保证顺序）。
        List<CreatorFeedbackItemRecord> ordered = new ArrayList<>();
        for (String itemId : orderedItemIds) {
            CreatorFeedbackItemRecord record = recordByItemId.get(itemId);
            if (record != null) {
                ordered.add(record);
            }
        }
        return ordered;
    }

    /**
     * 从 Milvus Document 中提取 itemId。
     * <p>
     * 优先读 metadata.itemId（索引时写入的非空字段），回退到 document.getId()。
     * 索引时 embedding_id 复用 item_id，两者应该一致，但双检查更安全。
     *
     * @param document Milvus 返回的 Document
     * @return itemId 字符串，无法提取时返回 null
     */
    private String extractItemId(Document document) {
        if (document == null) {
            return null;
        }
        // 索引时 embedding_id 复用 item_id，且 metadata 也存了 itemId；优先读 metadata，回退文档 id。
        Object metadataItemId = document.getMetadata() == null ? null : document.getMetadata().get("itemId");
        if (metadataItemId != null && TextUtil.hasText(metadataItemId.toString())) {
            return metadataItemId.toString();
        }
        return TextUtil.trimToNull(document.getId());
    }

    /**
     * 按 itemId 去重合并两条检索路径的证据。
     * <p>
     * primary（向量结果）排前面（语义更相关），secondary（SQL 结果）补后面，
     * 用 LinkedHashMap 按 itemId 去重并保持插入顺序。列表截断到 limit。
     *
     * @param primary 主证据列表（向量检索结果，排前面）
     * @param secondary 补充证据列表（SQL 评分结果）
     * @param limit 最终返回的条数上限
     * @return 去重后的合并列表
     */
    private List<CreatorFeedbackItemRecord> mergeDistinctById(List<CreatorFeedbackItemRecord> primary,
                                                              List<CreatorFeedbackItemRecord> secondary,
                                                              int limit) {
        Map<String, CreatorFeedbackItemRecord> merged = new LinkedHashMap<>();
        for (CreatorFeedbackItemRecord record : primary) {
            if (record.getItemId() != null) {
                merged.putIfAbsent(record.getItemId(), record);
            }
        }
        for (CreatorFeedbackItemRecord record : secondary) {
            if (merged.size() >= limit) {
                break;
            }
            if (record.getItemId() != null) {
                merged.putIfAbsent(record.getItemId(), record);
            }
        }
        return limit(new ArrayList<>(merged.values()), limit);
    }

    private List<CreatorFeedbackItemRecord> limit(List<CreatorFeedbackItemRecord> records, int limit) {
        if (records.size() <= limit) {
            return records;
        }
        return new ArrayList<>(records.subList(0, limit));
    }

    // ============================ SQL 轻量匹配（从 CreatorFeedbackService 搬入） ============================
    // SQL 检索链路：question → 分词 → 关键词命中评分 + 意图加权 + 社交信号加分 → TopK

    /**
     * 基于关键词命中 + 问题意图打分，选出最相关的证据。
     * <p>
     * 评分维度（按权重排序）：
     * <ol>
     *   <li><b>问题意图匹配</b>（最高权重，+4~+5）：如用户问”误解”则提升 QUESTION/DOUBT 分类条目</li>
     *   <li><b>关键词命中</b>（中等权重，+2~+3）：对问题做 2-gram/3-gram 分词，命中按长度加权</li>
     *   <li><b>社交信号加分</b>（低权重，+1 each）：有赞/有回复的条目优先，因为被其他用户认可的内容通常更有参考价值</li>
     * </ol>
     * <p>
     * 二级排序：分数降序 → 点赞降序 → 回复降序 → ID 降序（保证同分下的稳定排序）。
     * 没有命中时返回少量最新有效样例，让模型能判断”证据不足”而不是凭空想象。
     *
     * @param question 用户追问
     * @param items 当前任务全部有效明细（已按噪声过滤）
     * @return TopK 证据列表
     */
    private List<CreatorFeedbackItemRecord> selectSqlEvidence(String question, List<CreatorFeedbackItemRecord> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, ragProperties.getTopK());
        List<String> terms = buildQuestionTerms(question);
        List<ScoredFeedbackItem> scoredItems = items.stream()
                .filter(item -> !Boolean.TRUE.equals(item.getNoise()))
                .map(item -> new ScoredFeedbackItem(item, scoreEvidenceItem(question, terms, item)))
                .filter(item -> item.score() > 0)
                .sorted(Comparator
                        .comparingInt(ScoredFeedbackItem::score).reversed()
                        .thenComparing(item -> nullableLongValue(item.record().getLikeCount()), Comparator.reverseOrder())
                        .thenComparing(item -> nullableLongValue(item.record().getReplyCount()), Comparator.reverseOrder())
                        .thenComparing(item -> nullableLongValue(item.record().getId()), Comparator.reverseOrder()))
                .limit(limit)
                .toList();
        if (!scoredItems.isEmpty()) {
            return scoredItems.stream().map(ScoredFeedbackItem::record).toList();
        }

        return items.stream()
                .filter(item -> !Boolean.TRUE.equals(item.getNoise()))
                .limit(Math.min(limit, 5))
                .toList();
    }

    /**
     * 将用户问题分词为检索词条。
     * <p>
     * 分词策略：
     * <ul>
     *   <li>ASCII 连续字符（如 "Spring AI"、"LLM"）：提取为完整词条</li>
     *   <li>中文字符：做 2-gram 和 3-gram 滑动窗口分词（如"观众为什么不懂" →
     *       "观众"、"众为"、"为什"、"什么"、"么不"、"不懂"、"观众为"、"众为什"…）</li>
     * </ul>
     * <p>
     * 为什么不用 jieba 分词：项目不为了一个轻量关键词匹配引入第三方中文分词库，
     * 2-gram/3-gram 的召回率对短追问（通常 10~30 字）足够，且结果完全可预测。
     * 32 条上限防止长问题生成过多无效词条。
     *
     * @param question 用户追问
     * @return 去重后的词条列表（保留顺序）
     */
    private List<String> buildQuestionTerms(String question) {
        if (TextUtil.isBlank(question)) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        Matcher asciiMatcher = Pattern.compile("[0-9A-Za-z]{2,}").matcher(question);
        while (asciiMatcher.find()) {
            terms.add(asciiMatcher.group().toLowerCase(Locale.ROOT));
        }

        String hanText = question.replaceAll("[^\\p{IsHan}]", "");
        int gramLimit = 32;
        for (int index = 0; index + 1 < hanText.length() && terms.size() < gramLimit; index++) {
            terms.add(hanText.substring(index, index + 2));
        }
        for (int index = 0; index + 2 < hanText.length() && terms.size() < gramLimit; index++) {
            terms.add(hanText.substring(index, index + 3));
        }
        return new ArrayList<>(terms);
    }

    /**
     * 对单条证据评分（关键词命中 + 意图匹配 + 社交信号）。
     * <p>
     * 关键词命中按长度加权：>= 3 字词条 +3（精准匹配），2 字词条 +2（可能只是巧合）。
     * 意图匹配通过问题关键词和条目分类/情绪的交叉判断实现：如用户提到"争议"则
     * DOUBT/COMPLAINT/NEGATIVE 类条目大幅加分。
     * 社交信号（赞/回复）只加 1 分——权重低是因为高赞不代表"相关"，
     * 只是同分时的排序依据（"两条都相关，优先选观众认可度更高的"）。
     *
     * @param question 用户追问
     * @param terms 预分词的检索词条
     * @param item 待评证明细
     * @return 综合评分
     */
    private int scoreEvidenceItem(String question, List<String> terms, CreatorFeedbackItemRecord item) {
        String content = TextUtil.trimToDefault(item.getContent(), "").toLowerCase(Locale.ROOT);
        String normalizedQuestion = TextUtil.trimToDefault(question, "").toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (content.contains(term.toLowerCase(Locale.ROOT))) {
                score += term.length() >= 3 ? 3 : 2;
            }
        }
        score += scoreByQuestionIntent(normalizedQuestion, item);
        if (item.getLikeCount() != null && item.getLikeCount() > 0) {
            score += 1;
        }
        if (item.getReplyCount() != null && item.getReplyCount() > 0) {
            score += 1;
        }
        return score;
    }

    /**
     * 基于问题意图的加权评分。
     * <p>
     * 意图匹配规则基于创作者复盘中的典型追问模式：
     * <ul>
     *   <li>"误解/不懂/没理解" → QUESTION / DOUBT / COMPLAINT（观众认知偏差类问题）</li>
     *   <li>"争议/质疑/负面" → DOUBT / COMPLAINT / NEGATIVE（内容争议类问题）</li>
     *   <li>"问题/为什么/怎么" → QUESTION（普通提问）</li>
     *   <li>"建议/下期/改进" → SUGGESTION（方向建议类问题）</li>
     *   <li>"喜欢/认可/有用" → APPROVAL / POSITIVE（正向反馈类问题）</li>
     * </ul>
     * <p>
     * 这些规则通过人工经验提炼，非机器学习模型。虽然召回率不完美，
     * 但规则完全可解释，创作者能看到"为什么推荐这条证据"，而不是黑盒结果。
     *
     * @param question 用户追问（已转小写）
     * @param item 待评证明细
     * @return 意图匹配分数（0/4/5）
     */
    private int scoreByQuestionIntent(String question, CreatorFeedbackItemRecord item) {
        String category = TextUtil.trimToDefault(item.getCategory(), "");
        String sentiment = TextUtil.trimToDefault(item.getSentiment(), "");
        int score = 0;
        if (containsAny(question, "误解", "不懂", "没理解", "看不懂")
                && List.of("QUESTION", "QUESTION_POINT", "DOUBT", "COMPLAINT").contains(category)) {
            score += 5;
        }
        if (containsAny(question, "争议", "质疑", "反对", "负面", "风险")
                && (List.of("DOUBT", "COMPLAINT").contains(category) || "NEGATIVE".equals(sentiment))) {
            score += 5;
        }
        if (containsAny(question, "问题", "为什么", "怎么", "提问")
                && List.of("QUESTION", "QUESTION_POINT").contains(category)) {
            score += 4;
        }
        if (containsAny(question, "建议", "下期", "选题", "改进")
                && "SUGGESTION".equals(category)) {
            score += 4;
        }
        if (containsAny(question, "喜欢", "认可", "正向", "有用")
                && (List.of("APPROVAL", "RESONANCE", "KNOWLEDGE_REACTION").contains(category)
                || "POSITIVE".equals(sentiment))) {
            score += 4;
        }
        return score;
    }

    private boolean containsAny(String content, String... keywords) {
        if (content == null) {
            return false;
        }
        String lowerValue = content.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lowerValue.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 安全地获取 Long 值，null 时返回 0L。
     * 用于排序时的空安全比较——Comparator 不处理 null 会导致 NPE。
     */
    private Long nullableLongValue(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 安全地获取 Long 值（Integer 版本），null 时返回 0L。
     */
    private Long nullableLongValue(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    /**
     * 带上评分的证据条目（内部数据结构，不暴露到 API）。
     *
     * @param record 评论弹幕明细记录
     * @param score  综合评分（关键词 + 意图 + 社交信号）
     */
    private record ScoredFeedbackItem(
            CreatorFeedbackItemRecord record,
            int score
    ) {
    }
}
