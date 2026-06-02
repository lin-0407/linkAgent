package com.link.linkagent.creator.feedback.service;

import com.link.linkagent.creator.feedback.config.CreatorFeedbackRagProperties;
import com.link.linkagent.creator.feedback.mapper.CreatorFeedbackMapper;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackEvidenceRetrievalResult;
import com.link.linkagent.creator.feedback.model.CreatorFeedbackItemRecord;
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
 * 反馈追问证据检索服务。
 * <p>
 * 采用“双层证据检索”：RAG 启用且 Milvus 可用时优先做语义检索，命中不足或运行期异常时回退到 SQL 轻量匹配。
 * <p>
 * 关键点：Milvus 只负责“找可能相关的证据 itemId”，MySQL 仍然是当前任务评论弹幕明细的事实来源。
 * 向量召回的 itemId 必须用 taskId + is_deleted = 0 回查 MySQL，避免向量库里的旧导入批次或脏数据直接进入回答。
 * <p>
 * 本服务还承接了原先写在 CreatorFeedbackService 里的 SQL 证据评分逻辑：因为“选证据”本就是检索职责，
 * 搬到这里能让 CreatorFeedbackService.chat 退化为编排者，也避免“服务反过来回调 CreatorFeedbackService”造成循环依赖。
 */
@Service
public class CreatorFeedbackEvidenceRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(CreatorFeedbackEvidenceRetrievalService.class);

    private final CreatorFeedbackRagProperties ragProperties;
    private final CreatorFeedbackMapper creatorFeedbackMapper;
    // 用 ObjectProvider 获取可选 VectorStore：默认 VECTOR_STORE_TYPE=none 时没有这个 Bean，不能强依赖，否则启动直接失败。
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    public CreatorFeedbackEvidenceRetrievalService(CreatorFeedbackRagProperties ragProperties,
                                                   CreatorFeedbackMapper creatorFeedbackMapper,
                                                   ObjectProvider<VectorStore> vectorStoreProvider) {
        this.ragProperties = ragProperties;
        this.creatorFeedbackMapper = creatorFeedbackMapper;
        this.vectorStoreProvider = vectorStoreProvider;
    }

    /**
     * 选出本次追问可引用的证据。
     *
     * @param taskId   当前任务（已由上层确认存在）
     * @param question 用户追问
     * @param items    当前任务全部有效明细，用于 SQL 兜底评分，避免重复查库
     */
    public CreatorFeedbackEvidenceRetrievalResult retrieve(String taskId,
                                                           String question,
                                                           List<CreatorFeedbackItemRecord> items) {
        boolean ragEnabled = ragProperties.isEnabled();
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

    /**
     * 基于关键词命中和问题意图打分，选出最相关的证据；没有明确命中时返回少量最新有效样例，
     * 让模型能判断“证据不足”而不是凭空回答。
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

    private Long nullableLongValue(Long value) {
        return value == null ? 0L : value;
    }

    private Long nullableLongValue(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    private record ScoredFeedbackItem(
            CreatorFeedbackItemRecord record,
            int score
    ) {
    }
}
