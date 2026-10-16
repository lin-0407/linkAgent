package com.link.linkagent.memory;

import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 长期记忆入口：封装 MySQL 读写、向量化与三维打分检索。
 * <p>
 * 记录身份是数据库主键 id（设计文档 D13 / E1）：同一条记忆内容变化沿用同一个 id，不同事实即使落到
 * 同一个 memory_key 也各自成条，不再由唯一键决定谁覆盖谁。memory_key 只在管理页做分类展示。
 * LLM 抽取由 {@link LongTermMemoryExtractor} 与摘要压缩链路各自负责。
 *
 * <h3>读取路径（设计文档 D1 / D11 / D6）</h3>
 * {@link #retrieveForContext(String, String)} 取代了原来的「按更新时间取最近 N 条」：
 * 取该用户全部未删除记忆 → 三维打分（时效性 / 相关性 / 重要性）→ 取 top-K → 把入选记忆的访问时间写回。
 * 打分口径、权重出处与归一化方式见 {@link MemoryScorer} 与 {@link MemoryRetrievalProperties}。
 *
 * <h3>向量能力可以缺席</h3>
 * 向量由 {@link MemoryEmbeddingService} 负责。配置没开（{@code EMBEDDING_MODEL_TYPE=none}）
 * 或调用失败时相关性维度为 0，排序退化成「时效 + 重要」两维，读取路径本身不会失败。
 */
@Component
public class LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);

    private static final String EXAMPLE_LANGUAGE_KEY = "user.preference.example_language";
    private static final String EXPLANATION_STYLE_KEY = "user.preference.explanation_style";
    private static final String USER_PROFILE_KEY = "user.profile.summary";
    private static final String PROJECT_PROFILE_KEY = "project.profile.summary";
    private static final String PROJECT_CONSTRAINT_KEY = "project.constraint.summary";

    private final LongTermMemoryMapper longTermMemoryMapper;
    private final MemoryEmbeddingService embeddingService;
    private final MemoryRetrievalProperties retrievalProperties;

    /** 单测构造器：不接向量能力（相关性维度恒为 0），检索参数取默认值。 */
    public LongTermMemory(LongTermMemoryMapper longTermMemoryMapper) {
        this(longTermMemoryMapper, MemoryEmbeddingService.disabled(), new MemoryRetrievalProperties());
    }

    @Autowired
    public LongTermMemory(LongTermMemoryMapper longTermMemoryMapper,
                          MemoryEmbeddingService embeddingService,
                          MemoryRetrievalProperties retrievalProperties) {
        this.longTermMemoryMapper = longTermMemoryMapper;
        this.embeddingService = embeddingService;
        this.retrievalProperties = retrievalProperties;
    }

    /**
     * 不带分数的写入，供记忆管理页手工创建使用（用户手写的记忆没有模型打分）。
     * 自动抽取的链路（预算累加器批量抽取、压缩时的高分事实点）都走带分数的重载。
     * 0 表示未打分，与数据库默认值一致，检索排序时按 {@link MemoryScorer#UNSCORED_IMPORTANCE} 的兜底分参与。
     */
    public Optional<LongTermMemoryRecord> save(String userId, String memoryKey, String content, String sourceSessionId) {
        return save(userId, memoryKey, content, sourceSessionId, 0);
    }

    /**
     * 带重要性分数的写入，供摘要压缩时的高分事实点与批量抽取使用（设计文档 B3 / D3）。
     * <p>
     * 写入即新增一条记录：同 key 的旧事实不再被覆盖，两条矛盾事实的取舍交给步骤 7 的两阶段写入决策，
     * 数据库不再替模型做判断（设计文档 D13）。
     * <p>
     * 向量随插入一次写入（D4）。向量化失败只是让这条记忆暂时没有相关性维度，
     * 下一次读取的补算路径会补上，不会因为一次网络抖动丢掉记忆。
     *
     * @param importance 重要性分数，1-100；0 表示未打分
     * @return 回读到的完整记录（含自增 id 与数据库生成的时间戳）；回读不到时为空，由调用方决定如何提示
     */
    public Optional<LongTermMemoryRecord> save(String userId, String memoryKey, String content,
                                               String sourceSessionId, int importance) {
        String normalizedUserId = userId.trim();
        LongTermMemoryRecord record = new LongTermMemoryRecord();
        record.setUserId(normalizedUserId);
        record.setMemoryKey(normalizeMemoryKey(memoryKey));
        record.setContent(content.trim());
        record.setImportance(normalizeImportance(importance));
        record.setSourceSessionId(TextUtil.trimToNull(sourceSessionId));
        record.setEmbedding(embedToJson(record.getContent()));
        longTermMemoryMapper.insert(record);
        // create_time / update_time 由数据库默认值生成，这里按新主键回读，避免在 Java 侧另算一份可能与库里不一致的时间。
        return longTermMemoryMapper.findActiveById(normalizedUserId, record.getId());
    }

    /** 越界分数按边界收敛：脏分数会直接进入三维打分，不能原样入库。 */
    private int normalizeImportance(int importance) {
        return Math.min(100, Math.max(0, importance));
    }

    /** 向量化一段内容并序列化；没有向量能力、内容为空或调用失败时返回 null（库里存 NULL）。 */
    private String embedToJson(String content) {
        if (!embeddingService.available() || TextUtil.isBlank(content)) {
            return null;
        }
        return embeddingService.toJson(embeddingService.embedOne(content));
    }

    /**
     * 按记忆身份（主键 id）精确查询一条有效记忆。
     * 同时带 userId 过滤，避免跨用户读到别人的记忆——单用户部署下这层校验也要在 SQL 里兜住。
     */
    public Optional<LongTermMemoryRecord> findById(String userId, Long id) {
        return longTermMemoryMapper.findActiveById(userId.trim(), id);
    }

    /** 管理页用的全量列表：不带向量列，也不做 top-K（管理页要浏览和搜索全部记忆）。 */
    public List<LongTermMemoryRecord> listByUser(String userId) {
        return longTermMemoryMapper.listByUser(userId.trim());
    }

    /**
     * 取出本次要注入上下文的记忆（设计文档 D1）。
     * <p>
     * 查询文本是本轮用户输入：记忆里的事实要在话题匹配时被唤醒，靠的是相关性这一维而不是重要性（D11 第 1 条）。
     *
     * @param userId    用户标识
     * @param queryText 本轮用户输入；为空时相关性维度为 0，退化成两维排序
     * @return top-K 记忆，输出顺序按 id 升序（D12）；入选记忆的访问时间已写回（D6）
     */
    public List<LongTermMemoryRecord> retrieveForContext(String userId, String queryText) {
        List<String> queryTexts = TextUtil.hasText(queryText) ? List.of(queryText) : List.of();
        // 只有这条路径写回访问时间：注入上下文才算「被取用」，写入决策的召回不写（见 retrieveForDecision）
        RetrievalOutcome outcome = retrieve(userId, queryTexts, retrievalProperties.topK(), true);
        List<LongTermMemoryRecord> memories = stableOrder(outcome.selected());
        logRetrieval(userId, outcome, memories);
        return memories;
    }

    /**
     * 取出与候选事实相关的已有记忆，供写入决策做去重与冲突判断（设计文档 C11）。
     * <p>
     * 每条候选各召回一次再取并集：把 5 条主题不同的候选拼成一段文本会让查询向量变成一个平均值，
     * 「只和其中一条冲突」的既有记忆反而召不回来。
     * <p>
     * 不回写访问时间：这些记忆只是被决策过程看了一眼，不算被取用，写回会污染时效性维度。
     *
     * @param userId     用户标识
     * @param queryTexts 候选事实的文本列表
     * @param limit      最多返回多少条
     * @return 按总分降序的相关记忆
     */
    public List<LongTermMemoryRecord> retrieveForDecision(String userId, List<String> queryTexts, int limit) {
        List<String> queries = new ArrayList<>();
        if (queryTexts != null) {
            for (String text : queryTexts) {
                if (TextUtil.hasText(text)) {
                    queries.add(text);
                }
            }
        }
        RetrievalOutcome outcome = retrieve(userId, queries, Math.max(1, limit), false);
        // 决策召回不进每轮上下文，没有前缀缓存可保，保持「最相关的排前面」的顺序
        List<LongTermMemoryRecord> memories = toRecords(outcome.selected());
        logRetrieval(userId, outcome, memories);
        return memories;
    }

    /**
     * 检索主体：全量候选 → （必要时）补算向量 → 三维打分 → 取 top-K。
     *
     * @param touchAccess 是否把本次入选记为「被取用」并写回访问时间
     */
    private RetrievalOutcome retrieve(String userId, List<String> queryTexts, int topK, boolean touchAccess) {
        String normalizedUserId = userId.trim();
        List<LongTermMemoryRecord> candidates = longTermMemoryMapper.listActiveForRetrieval(normalizedUserId);
        if (candidates.isEmpty()) {
            return new RetrievalOutcome(0, List.of(), false);
        }
        boolean vectorEnabled = embeddingService.available();
        if (vectorEnabled) {
            // 存量记忆、写入时向量化失败的记忆、以及管理页手工新建的记忆，都在这里补上：读取路径负责自愈（D4）
            backfillEmbeddings(normalizedUserId, candidates);
        }
        List<float[]> queryVectors = vectorEnabled && !queryTexts.isEmpty()
                ? embeddingService.embedAll(queryTexts)
                : List.of();
        LocalDateTime now = LocalDateTime.now();
        List<MemoryScorer.ScoredMemory> scored = new ArrayList<>(candidates.size());
        for (LongTermMemoryRecord record : candidates) {
            float[] memoryVector = vectorEnabled ? embeddingService.parse(record.getEmbedding()) : null;
            double relevance = 0.0;
            for (float[] queryVector : queryVectors) {
                // 多条查询取最大值：只要有一条候选事实与它相关，这条记忆就值得带进决策上下文
                relevance = Math.max(relevance,
                        MemoryScorer.relevance(MemoryEmbeddingService.cosine(queryVector, memoryVector)));
            }
            double recency = MemoryScorer.recency(now, record.getLastAccessedTime(), record.getCreateTime(),
                    retrievalProperties.recencyDecayPerHour());
            double importance = MemoryScorer.importance(record.getImportance());
            scored.add(new MemoryScorer.ScoredMemory(record, recency, relevance, importance,
                    MemoryScorer.weighted(recency, relevance, importance, retrievalProperties)));
        }
        // 同分按 id 升序兜底：这里排的是「谁入选」，同分时不让两条记忆在两次检索之间互换去留
        scored.sort(Comparator.comparingDouble(MemoryScorer.ScoredMemory::score).reversed()
                .thenComparing(item -> item.memory().getId() == null ? Long.MAX_VALUE : item.memory().getId()));
        List<MemoryScorer.ScoredMemory> selected = scored.subList(0, Math.min(topK, scored.size()));
        if (touchAccess) {
            markAccessed(normalizedUserId, toRecords(selected), now);
        }
        return new RetrievalOutcome(candidates.size(), List.copyOf(selected), !queryVectors.isEmpty());
    }

    /**
     * 按记忆 id 升序重排输出顺序（设计文档 D12 的稳定键）。
     * <p>
     * 打分只决定「选谁」，不决定「按什么顺序发出去」：这段记忆拼在整个上下文的最前面，
     * 顺序一变，服务端的前缀缓存整段作废，后面所有内容都要重新计费、重新计算。
     * id 是自增的，所以这个顺序天然满足「老记忆位置不动、新记忆追加到底部」。
     * <p>
     * 已知取舍（D12 已确认）：某一轮有记忆掉出时，它后面的记忆位置会前移，那一轮前缀失效；
     * 不做占位符——占位符是每轮都要付 token 的代价。
     */
    private List<LongTermMemoryRecord> stableOrder(List<MemoryScorer.ScoredMemory> selected) {
        List<LongTermMemoryRecord> memories = toRecords(selected);
        // id 为空（理论上不会出现）排到最后，不让它插在中间打乱整体顺序
        memories.sort(Comparator.comparing(item -> item.getId() == null ? Long.MAX_VALUE : item.getId()));
        return memories;
    }

    private List<LongTermMemoryRecord> toRecords(List<MemoryScorer.ScoredMemory> selected) {
        List<LongTermMemoryRecord> memories = new ArrayList<>(selected.size());
        for (MemoryScorer.ScoredMemory item : selected) {
            memories.add(item.memory());
        }
        return memories;
    }

    /** 一次检索的结果：候选总数、按分数排序的命中明细、这次是否真的算出了相关性（日志与调用方共用）。 */
    private record RetrievalOutcome(int candidateCount, List<MemoryScorer.ScoredMemory> selected,
                                    boolean relevanceEnabled) {
    }

    /**
     * 读取路径上的向量补算。
     * <p>
     * 一次读取最多补 {@code embedding-backfill-limit} 条：存量记忆多的时候，
     * 一次读取打出几十个向量化请求会把这一轮对话拖住，剩下的下一次读取继续补。
     */
    private void backfillEmbeddings(String userId, List<LongTermMemoryRecord> candidates) {
        int limit = retrievalProperties.embeddingBackfillLimit();
        if (limit <= 0) {
            return;
        }
        List<LongTermMemoryRecord> missing = new ArrayList<>();
        for (LongTermMemoryRecord record : candidates) {
            if (TextUtil.isBlank(record.getEmbedding()) && TextUtil.hasText(record.getContent())) {
                missing.add(record);
                if (missing.size() >= limit) {
                    break;
                }
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        List<String> texts = new ArrayList<>(missing.size());
        for (LongTermMemoryRecord record : missing) {
            texts.add(record.getContent());
        }
        List<float[]> vectors = embeddingService.embedAll(texts);
        for (int index = 0; index < missing.size(); index++) {
            float[] vector = vectors.get(index);
            LongTermMemoryRecord record = missing.get(index);
            if (vector == null || record.getId() == null) {
                continue;
            }
            String json = embeddingService.toJson(vector);
            if (json == null) {
                continue;
            }
            // 先写库再回填内存：本次检索马上要用它算相关性，写库失败也不影响这一次的排序（下一轮读取会再补一次）。
            // 向量是派生数据，它的写入失败不该让这一轮对话失败——读路径的失败仍然照常抛出。
            try {
                longTermMemoryMapper.updateEmbedding(userId, record.getId(), json);
            } catch (RuntimeException exception) {
                log.warn("长期记忆向量写入失败（本次排序仍使用刚算出的向量），userId={}, id={}, error={}",
                        userId, record.getId(), exception.getMessage());
            }
            record.setEmbedding(json);
        }
        log.info("长期记忆向量补算完成，userId={}, 本次补算 {} 条", userId, missing.size());
    }

    /**
     * 把本次入选的记忆记为「被取用」（D6）：批量一条 SQL，并同步内存里的值供日志与调用方查看。
     * <p>
     * 写回失败只告警不抛出：这条改造把读取路径从「只读」变成「带一次辅助写」，
     * 而访问时间只是排序用的派生数据，不该因为它写不进去就让用户这一轮拿不到回答。
     * 记忆本身的读取（{@code listActiveForRetrieval}）失败仍然照常抛出——那是真的拿不到数据。
     */
    private void markAccessed(String userId, List<LongTermMemoryRecord> memories, LocalDateTime accessedTime) {
        List<Long> ids = new ArrayList<>(memories.size());
        for (LongTermMemoryRecord memory : memories) {
            if (memory.getId() != null) {
                ids.add(memory.getId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        try {
            longTermMemoryMapper.markAccessed(userId, ids, accessedTime);
        } catch (RuntimeException exception) {
            log.warn("长期记忆访问时间写回失败（不影响本轮回答），userId={}, 条数={}, error={}",
                    userId, ids.size(), exception.getMessage());
            return;
        }
        for (LongTermMemoryRecord memory : memories) {
            memory.setLastAccessedTime(accessedTime);
        }
    }

    /**
     * 检索日志分两段：明细按分数排（调权重、排查误召回要看三个维度各自的贡献），
     * 输出顺序单独列（D12 之后「选中谁」与「按什么顺序发出去」是两件事，前缀缓存看的是后者）。
     */
    private void logRetrieval(String userId, RetrievalOutcome outcome, List<LongTermMemoryRecord> emitted) {
        StringBuilder detail = new StringBuilder();
        for (MemoryScorer.ScoredMemory item : outcome.selected()) {
            if (!detail.isEmpty()) {
                detail.append(", ");
            }
            detail.append(item.memory().getId() == null ? "?" : item.memory().getId())
                    .append("(时效").append(String.format("%.3f", item.recency()))
                    .append("/相关").append(String.format("%.3f", item.relevance()))
                    .append("/重要").append(String.format("%.2f", item.importance()))
                    .append("→").append(String.format("%.3f", item.score())).append(")");
        }
        StringBuilder order = new StringBuilder();
        for (LongTermMemoryRecord memory : emitted) {
            if (!order.isEmpty()) {
                order.append(',');
            }
            order.append(memory.getId() == null ? "?" : memory.getId());
        }
        log.info("长期记忆检索完成，userId={}, 候选 {} 条, 选中 {} 条, 相关性维度={}, 输出顺序=[{}], 明细=[{}]",
                userId, outcome.candidateCount(), outcome.selected().size(),
                outcome.relevanceEnabled() ? "启用" : "降级（无向量能力或查询为空）", order, detail);
    }

    /**
     * 按写入决策里的 UPDATE 覆盖已有记忆的内容与分数。
     * <p>
     * 沿用原 id 是设计文档 D8 的要求：同一条记忆换了说法，身份不变，管理页不会看到「凭空多出一条」。
     * 阶段二决策器是唯一调用方。
     */
    public void update(String userId, Long id, String content, int importance) {
        String normalizedUserId = userId.trim();
        String normalizedContent = content.trim();
        longTermMemoryMapper.updateContent(normalizedUserId, id, normalizedContent, normalizeImportance(importance));
        // 内容变了向量必须跟着重算：否则相关性维度还在用旧内容的向量，更新过的记忆会被持续误召回
        String embeddingJson = embedToJson(normalizedContent);
        if (embeddingJson != null) {
            longTermMemoryMapper.updateEmbedding(normalizedUserId, id, embeddingJson);
        }
    }

    /**
     * 按写入决策里的 DELETE 把记忆置为失效。
     * <p>
     * 与用户删除区分：deleted_by_user 保持 0，因此它不会出现在可恢复列表里（设计文档 D15）；
     * supersededBy 非空表示这条记忆是被某条新事实取代的。
     */
    public void markDeleted(String userId, Long id, Long supersededBy) {
        longTermMemoryMapper.markDeletedByDecision(userId.trim(), id, supersededBy);
    }

    /**
     * 用户主动删除一条记忆（软删除）。
     * 与「被新事实取代」区分开：只有这里删掉的记录才允许恢复（设计文档 D15）。
     */
    public void delete(String userId, Long id) {
        longTermMemoryMapper.softDelete(userId.trim(), id);
    }

    /**
     * 撤销用户主动删除。
     * 撤销只恢复删除标记，不能借用 save 覆盖原内容、来源会话或向量标识；被取代的记忆恢复不到，返回空。
     */
    public Optional<LongTermMemoryRecord> restore(String userId, Long id) {
        String normalizedUserId = userId.trim();
        longTermMemoryMapper.restore(normalizedUserId, id);
        return longTermMemoryMapper.findActiveById(normalizedUserId, id);
    }

    /**
     * memory_key 归一化：把模型给的自由文本分类映射到固定几类，只影响管理页的展示分组。
     * 改成 id 身份后它不再参与唯一性判断，因此归类不准的代价只是「分类标签不好看」，不会丢记忆。
     */
    private String normalizeMemoryKey(String memoryKey) {
        String normalized = memoryKey.trim().toLowerCase();
        if (containsAny(normalized, "language", "programming_language", "example")) {
            return EXAMPLE_LANGUAGE_KEY;
        }
        if (containsAny(normalized, "explain", "explanation", "style", "answer_style")) {
            return EXPLANATION_STYLE_KEY;
        }
        if (normalized.startsWith("user.profile") || containsAny(normalized, "learning", "career", "role")) {
            return USER_PROFILE_KEY;
        }
        if (normalized.startsWith("project.profile") || containsAny(normalized, "stack", "goal", "portfolio")) {
            return PROJECT_PROFILE_KEY;
        }
        if (normalized.startsWith("project.constraint") || containsAny(normalized, "constraint", "rule")) {
            return PROJECT_CONSTRAINT_KEY;
        }
        return normalized;
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
