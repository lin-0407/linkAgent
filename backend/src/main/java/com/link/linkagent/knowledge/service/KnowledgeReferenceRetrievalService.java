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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨分区视频案例库检索服务（阶段 5.2a：最小检索闭环）。
 * <p>
 * 在知识库 RAG 架构中的位置：<b>检索层（Retrieval）</b>——接收用户查询和过滤条件，
 * 通过向量语义检索（dense / hybrid）或 SQL 关键词兜底找到匹配的参考视频案例卡片，
 * 是连接”用户查询”和”Milvus/MySQL 数据”的核心桥梁。
 * <p>
 * <b>三段式设计</b>（照搬反馈侧 {@code CreatorFeedbackEvidenceRetrievalService} 的成熟范式，
 * 反复验证过的稳定模式不应自行发明新形式）：
 * <ol>
 *   <li>RAG 启用且向量库就绪时，通过 dense 语义检索（或 hybrid dense+BM25+RRF）查 Milvus 拿到候选 videoId；</li>
 *   <li>用 videoId <b>回查 MySQL 父表事实源</b>（WHERE is_deleted=0），过滤掉向量库中残留的旧批次/已软删案例。
 *       父表才是真身——向量库是检索索引，可能因索引滞后而返回已删数据；必须回查才能得到可信结果。</li>
 *   <li>向量命中不足或向量库不可用时，SQL 关键词 LIKE 兜底，<b>优雅降级且不报错</b>。
 *       关键词兜底是最基础的保障——即使 Milvus 挂掉，创作者仍能通过 SQL 搜索找到相关案例。</li>
 * </ol>
 * <p>
 * <b>隔离约束</b>：知识库向量库不是 {@code VectorStore} 类型的 Spring Bean（因为不能和反馈侧
 * 向量库 Bean 同名冲突），必须通过 {@link KnowledgeVectorStore} 持有者间接获取，
 * <b>严禁</b>注入 {@code ObjectProvider<VectorStore>}（那是反馈侧集合，注入会抓到错误的向量库 Bean）。
 * <p>
 * <b>简单优先的增量演进策略</b>：
 * 5.2a 只做 dense + SQL 兜底；查询改写/多查询/HyDE（5.2b）、子表父子召回（5.2c）、
 * 原生 BM25 混合（5.2d）、Rerank 精排（5.2e）按切片顺序后续渐进叠加。
 * 检索逻辑先落在 knowledge 模块内部，待阶段 5.3 Agent 内核统一入口、
 * 出现真实复用需求时再把共性能力上提到通用 rag 包——避免提前抽象造成的无用功。
 */
@Service
public class KnowledgeReferenceRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceRetrievalService.class);

    /** RAG 关闭或向量库不可用时，走纯 SQL 关键词兜底检索。 */
    public static final String MODE_SQL = "SQL";
    /** 向量语义检索命中足够，已回查父表事实源，未使用 SQL 兜底。 */
    public static final String MODE_VECTOR = "VECTOR";
    /** 向量语义检索命中不足，合并了 SQL 关键词兜底补足到 topK 条。 */
    public static final String MODE_VECTOR_WITH_SQL_FALLBACK = "VECTOR_WITH_SQL_FALLBACK";
    /** 原生 hybrid 检索（dense+BM25+RRF）命中足够，已回查父表事实源。 */
    public static final String MODE_HYBRID = "HYBRID";
    /** 原生 hybrid 检索命中不足，合并了 SQL 关键词兜底补足到 topK 条。 */
    public static final String MODE_HYBRID_WITH_SQL_FALLBACK = "HYBRID_WITH_SQL_FALLBACK";

    /**
     * 单次检索候选硬上限，与接口层 @Max(50) 对齐。
     * 这是二次防御——即使 KnowledgeRagProperties.topK 被误配成超大值（如 10000），
     * 实际检索结果也不会超过 50 条，防止撑爆内存和网络带宽。
     */
    private static final int MAX_TOP_K = 50;

    /**
     * 每张父卡片回显的子条目证据上限（阶段 5.2c-2）。
     * 理由：一张视频可能命中 20+ 条弹幕/评论子片段，全部回显会让前端卡片高度失控、
     * API 响应体积急剧膨胀。取前 3 条（按向量相似度最相关的），足够给用户样本感觉，
     * 其余命中仍参与 videoId 去重——子召回命中的视频即使只展示 3 条证据也完整登记进候选。
     */
    private static final int MAX_EVIDENCE_PER_VIDEO = 3;

    /**
     * 送 rerank 的单条候选文本字符上限（阶段 5.2e）。
     * 远低于 qwen3-rerank 单文档 4000 token 的限制，预留大量安全余量；
     * 超长案例简介通常尾部是重复/水词，截断不影响精排模型对卡片主题的判断。
     */
    private static final int RERANK_DOC_MAX_CHARS = 1500;

    /** SQL 关键词切词正则：匹配连续的中文汉字、英文字母、数字作为独立分词片段。 */
    private static final Pattern SQL_KEYWORD_PATTERN = Pattern.compile("[\\p{IsHan}A-Za-z0-9]+");

    /**
     * 允许的案例层级（tier）过滤值，与父表 tier 列语义一致。
     * 白名单校验的意义：非法 tier 值直接返回 400（Bad Request），而非静默返回空结果。
     * 如果静默空结果，调用方会误以为”这个层级没有数据”，浪费排查时间。
     */
    private static final Set<String> ALLOWED_TIERS = Set.of("BENCHMARK", "COMPETITOR", "OWN_HISTORY");

    /** RAG 运行期配置：总开关、topK、查询增强策略等参数均从此读取。 */
    private final KnowledgeRagProperties knowledgeRagProperties;

    /** 知识引用视频表的 Mapper，负责 MySQL 父表事实源查询和关键词兜底搜索。 */
    private final KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper;

    /**
     * 知识库专用向量库持有者（隔离 Bean）。
     * 不是 VectorStore 类型的 Spring Bean——直接注入 VectorStore 会抓到反馈侧的向量库。
     * 通过此持有者间接获取，确保操作的是知识库自己的 Milvus 集合。
     */
    private final KnowledgeVectorStore knowledgeVectorStore;

    /** 查询增强器（5.2b）：dense 检索前把原始 query 扩展为 1-N 条检索文本。 */
    private final KnowledgeQueryEnhancer knowledgeQueryEnhancer;

    /**
     * 重排客户端（5.2e）：检索候选定下来后，用 qwen3-rerank 模型根据原始 query 精排候选顺序。
     * 关闭或调用失败时返回空列表，后续逻辑保持原向量相似度顺序。
     */
    private final KnowledgeRerankClient knowledgeRerankClient;

    /** 原生 hybrid 存储（5.2d）：hybrid 子开关开且 Milvus 集合就绪时，父/子召回都改走 dense+BM25+RRF。 */
    private final KnowledgeHybridStore knowledgeHybridStore;

    /**
     * 运行期设置服务：rerank 是否启用由前端设置页动态控制（可随时开关，无需重启），
     * 其他检索参数（topK、增强策略等）仍沿用 application.yml 配置类。
     */
    private final RuntimeSettingService runtimeSettingService;

    public KnowledgeReferenceRetrievalService(KnowledgeRagProperties knowledgeRagProperties,
                                              KnowledgeReferenceVideoMapper knowledgeReferenceVideoMapper,
                                              KnowledgeVectorStore knowledgeVectorStore,
                                              KnowledgeQueryEnhancer knowledgeQueryEnhancer,
                                              KnowledgeRerankClient knowledgeRerankClient,
                                              KnowledgeHybridStore knowledgeHybridStore,
                                              RuntimeSettingService runtimeSettingService) {
        this.knowledgeRagProperties = knowledgeRagProperties;
        this.knowledgeReferenceVideoMapper = knowledgeReferenceVideoMapper;
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.knowledgeQueryEnhancer = knowledgeQueryEnhancer;
        this.knowledgeRerankClient = knowledgeRerankClient;
        this.knowledgeHybridStore = knowledgeHybridStore;
        this.runtimeSettingService = runtimeSettingService;
    }

    /**
     * 案例库检索主入口——唯一对外公开的检索方法。
     * <p>
     * 入参已由 Controller 层 @Valid 完成基本校验（null、范围等），这里再做业务层的归一化与兜底：
     * tier 大写归一 + 白名单校验、topK 收敛到 [1, 50]、查询增强策略枚举校验。
     * <p>
     * 检索流程（按优先级递减，后一步仅在前一步不足时触发）：
     * <ol>
     *   <li><b>查询增强（5.2b）</b>：按策略将原始 query 扩展为 1-N 条检索文本</li>
     *   <li><b>父卡片召回</b>：vector/hybrid 语义检索拿到候选 videoId 列表</li>
     *   <li><b>子条目召回（5.2c）</b>：small-to-big ——从子集合反查父 videoId，增加召回广度</li>
     *   <li><b>回查父表事实源</b>：用 videoId 查 MySQL，过滤已删案例</li>
     *   <li><b>SQL 关键词兜底</b>：向量命中 < minVectorHitCount 时合并 SQL 结果补足</li>
     *   <li><b>Rerank 精排（5.2e）</b>：用重排模型精排候选顺序（可选）</li>
     *   <li><b>组装证据</b>：对最终卡片中有子命中者，回查子表挂载证据片段</li>
     * </ol>
     *
     * @param request 检索请求（query 必填，category/tier/topK/strategy 可选）
     * @return 检索响应，含检索模式、增强查询列表、案例卡片、证据片段、重排标记
     */
    public ReferenceVideoSearchResponse search(ReferenceVideoSearchRequest request) {
        String query = request.query().trim();
        String category = TextUtil.trimToNull(request.category());
        String tier = normalizeTier(request.tier());
        int topK = resolveTopK(request.topK());
        QueryEnhanceStrategy strategy = resolveStrategy(request.strategy());

        boolean ragEnabled = knowledgeRagProperties.isEnabled();
        // 原生 hybrid 是否生效（5.2d-2）：三个条件同时满足才走 hybrid，任一不满足则降级。
        // 分三层检查而非合并为一个大表达式，是为了排查时能区分是"总开关关了"还是"hybrid 库就绪失败"。
        boolean hybridEnabled = ragEnabled
                && knowledgeRagProperties.getHybrid().isEnabled()
                && knowledgeHybridStore.isReady();
        // Spring AI 父向量库：仅在 hybrid 关闭时才尝试获取（两套召回源互斥，走 hybrid 就不需要这个 VectorStore）。
        // orElse(null) 意味着即使 knowledgeVectorStore 声称就绪，底层也可能返回空——防御 Milvus 状态与 Spring 管理器的不同步。
        VectorStore vectorStore = (ragEnabled && knowledgeVectorStore.isReady())
                ? knowledgeVectorStore.getVectorStore().orElse(null)
                : null;
        // 向量链路可用性判断：hybrid 就绪 或 Spring AI 父库就绪。两者都不可用时走纯 SQL 兜底。
        // hybrid 关时 hybridEnabled=false，本判断退化为原来的「vectorStore==null」判断，保证零回归。
        if (!hybridEnabled && vectorStore == null) {
            // RAG 关 / 向量库都不可用：SQL 兜底只用原始 query（未增强、无子证据、不重排）。
            // 回显 strategy=NONE 说明走了兜底而非指定策略；enhancedQueries 为空说明无增强发生；reranked=false。
            return new ReferenceVideoSearchResponse(
                    MODE_SQL, QueryEnhanceStrategy.NONE.name(), List.of(),
                    sqlFallback(query, category, tier, topK), List.of(), false);
        }

        // 查询增强（5.2b）：NONE 策略直接使用原 query 单条；其余策略委托增强器产出 1-N 条检索文本。
        // 增强器内部已保证失败降级——即使 LLM 调用失败也至少返回 [原始 query]，所以这里的结果列表非空。
        List<String> searchTexts = (strategy == QueryEnhanceStrategy.NONE)
                ? List.of(query)
                : knowledgeQueryEnhancer.enhance(query, strategy);
        if (searchTexts.isEmpty()) {
            searchTexts = List.of(query);
        }
        // 回显实际扩展的查询：NONE 不算增强，回显空列表；其余策略回显实际扩展的文本列表，
        // 便于前端/排查时核对增强产出是否合理（如 REWRITE 改写是否丢失了核心意图）
        List<String> enhancedQueries = (strategy == QueryEnhanceStrategy.NONE) ? List.of() : searchTexts;

        // 候选池宽度：开 rerank 时按更宽的 candidatePoolSize 召回。
        // 策略是先宽召回（retrieve-wide）再精排截断（rerank → topK），
        // 比"直接只召回 topK"多了一个精排步骤但能显著提升 topK 的结果质量。
        boolean rerankEnabled = runtimeSettingService.isKnowledgeRerankEnabled();
        int candidateK = rerankEnabled
                ? Math.min(MAX_TOP_K, Math.max(topK, knowledgeRagProperties.getRerank().getCandidatePoolSize()))
                : topK;

        // 父卡片召回：hybrid 开启时走原生 Milvus dense+BM25+RRF 混合检索，关闭时走 Spring AI 纯 dense 检索。
        // 运行期异常（如 Milvus 连接断开、超时）统一退回 SQL 兜底，确保用户在任何情况下都能拿到搜索结果。
        // 异常时返回 MODE_SQL 而非抛出 500——检索的可靠性优先于检索的质量。
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

        // 子召回的 small-to-big 策略（5.2c-2）：从子表向量集合反查父 videoId，增加召回广度。
        // 设计意图：用户查询"如何做开场白"时，如果父表卡片标题不直接包含此信息，但子表中的
        // 弹幕/评论片段直接提到了"开场白"，子召回能把这张卡片挖出来——这是 small-to-big 的核心价值。
        //
        // 分级兜底策略：
        // - 主题中块召回：异常只让 chunkVideoIds 回退空列表，父检索不受影响
        // - 子条目召回：异常只让 childMap 回退空 Map，父检索不受影响
        // 这种设计是因为 small-to-big 是锦上添花——没有子证据只是信息少一点，绝不应该因此中断检索
        //
        // childMap：videoId → 命中的 itemId 列表（有序，每卡截到 MAX_EVIDENCE_PER_VIDEO 条）
        //   - keySet()：参与 videoId 合并定序——子召回命中的视频和父召回命中的一起进候选
        //   - values()：供最终阶段回查子表组装证据
        List<String> chunkVideoIds = new ArrayList<>();
        if (!hybridEnabled && knowledgeVectorStore.isChunkReady()) {
            VectorStore chunkStore = knowledgeVectorStore.getChunkVectorStore().orElse(null);
            if (chunkStore != null) {
                try {
                    chunkVideoIds = chunkSearchMulti(chunkStore, searchTexts, category, tier, candidateK);
                } catch (Exception exception) {
                    log.warn("案例库主题中块召回失败，退回父子两层检索。query={}", TextUtil.preview(query, 60, ""), exception);
                    chunkVideoIds = List.of();
                }
            }
        }

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

        // 合并候选 videoId：父卡片在最前（整案一级匹配，相关度最高），主题中块其次（中粒度语义），
        // 子派生在最后（原始证据小块，粒度最细）。LinkedHashSet 去重保序取前 candidateK 条。
        // 此阶段不做跨层 RRF——那是 5.2d 混合搜索的事；这里只是把多个召回源的结果简单拼接去重。
        List<String> orderedVideoIds = mergeVideoIds(parentVideoIds, chunkVideoIds, childMap.keySet(), candidateK);

        // 事实来源回查：用有序 videoId 列表回查 MySQL 父表获取完整的案例卡片字段。
        // 关键——必须 WHERE is_deleted=0：向量库可能因索引延迟残留已软删的数据，回查事实源是唯一的可信校验。
        // reorderByVideoIds 保持向量相似度顺序（MySQL IN 查询不保证顺序）
        List<ReferenceVideoRecord> vectorRecords = orderedVideoIds.isEmpty()
                ? List.of()
                : reorderByVideoIds(knowledgeReferenceVideoMapper.listByVideoIds(orderedVideoIds), orderedVideoIds);

        // 候选模式判定：向量命中数 >= minVectorHitCount 为纯 VECTOR/HYBRID 模式，不足则合并 SQL 兜底。
        // minVectorHitCount 默认 1：意味着只要向量搜到至少 1 条相关结果就不用 SQL 兜底。
        // 配置允许调高此值（如 3），要求向量至少命中 3 条才算"足够"。
        // hybrid 开启时模式标签标 HYBRID 系而非 VECTOR 系，便于前端/排查区分本次走的是原生混合还是纯 dense。
        String mode;
        List<ReferenceVideoRecord> candidates;
        int minHit = Math.max(1, knowledgeRagProperties.getMinVectorHitCount());
        if (vectorRecords.size() >= minHit) {
            mode = hybridEnabled ? MODE_HYBRID : MODE_VECTOR;
            candidates = vectorRecords;
        } else {
            mode = hybridEnabled ? MODE_HYBRID_WITH_SQL_FALLBACK : MODE_VECTOR_WITH_SQL_FALLBACK;
            List<ReferenceVideoRecord> sqlRecords = sqlFallbackRecords(query, category, tier, candidateK);
            // 合并去重：向量结果优先（它的相似度顺序更有语义含义），SQL 结果补充
            candidates = mergeDistinctByVideoId(vectorRecords, sqlRecords, candidateK);
        }

        // Rerank 精排（5.2e，可选）：用原始 query（非经过改写/HyDE 扩展的文本）对候选卡片精排。
        // 用原始 query 而非扩展文本的原因：精排模型需要判断"卡片是否切合用户原始意图"，
        // 扩展文本可能偏离用户本意（如 HyDE 生成的是假设答案，用它做精排 query 会引入偏差）。
        // 关闭或调用失败时 rerankOrder 为空，后续保持原向量相似度顺序。
        List<Integer> rerankOrder = rerankEnabled
                ? knowledgeRerankClient.rerank(query, toRerankTexts(candidates))
                : List.of();
        boolean reranked = !rerankOrder.isEmpty();
        List<ReferenceVideoRecord> finalRecords = reranked
                ? limit(reorderByIndices(candidates, rerankOrder), topK)
                : limit(candidates, topK);

        // 组装证据：只对最终结果中"有子命中"的卡片挂载证据片段。
        // SQL 关键词兜底补进来的卡片天然无子证据——它们是通过关键词 LIKE 而非向量语义匹配进来的。
        return new ReferenceVideoSearchResponse(
                mode, strategy.name(), enhancedQueries,
                toResponses(finalRecords), buildEvidence(finalRecords, childMap), reranked);
    }

    // ============================ 向量检索 + 回查 ============================

    /**
     * 多路 dense 检索（阶段 5.2b）：对查询增强产出的 1-N 条文本各做一次向量语义检索，
     * 按 videoId 合并去重后返回前 topK 条。
     * <p>
     * 单条策略（NONE / REWRITE / HYDE）直接走单次检索，零额外开销。
     * 多条策略（MULTI_QUERY）逐条检索后用 LinkedHashSet 保序去重——先检索的文本命中的 videoId 排在前面，
     * 等价于"原始 query 的检索结果优先于变体查询"（因为 includeOriginal=true 时原 query 排在列表首位）。
     * <p>
     * 此阶段<b>不做 RRF 加权融合</b>——每条检索文本的 score 尺度不可直接比较
     * （不同文本的向量距离分布不同），正式多路融合方案留到阶段 5.2d 的原生 BM25 混合。
     *
     * @param vectorStore Spring AI 父表向量库（或任意 Milvus 集合对应的 VectorStore 实例）
     * @param searchTexts 1-N 条用于检索的文本（已通过查询增强产出）
     * @param category 分区过滤（空/null 表示不过滤）
     * @param tier 案例层级过滤（空/null 表示不过滤）
     * @param topK 期望返回的候选 videoId 数量上限
     * @return 去重后的有序 videoId 列表
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
     * 多路 hybrid 检索（阶段 5.2d-2）：对查询增强产出的 1-N 条文本各做一次原生 Milvus dense+BM25+RRF 检索，
     * 按 videoId 合并去重后返回前 topK 条。
     * <p>
     * 与 {@link #denseSearchMulti} 的唯一区别：每条检索用 {@link KnowledgeHybridStore#hybridSearch}
     * 替代 {@link #denseSearch}，即每条内部已经过 dense/BM25 的 RRF 加权融合。
     * 跨多条增强查询文本这层仍不做 RRF——与 denseSearchMulti 保持同口径。
     * <p>
     * 每条检索使用标记用量场景，将 BM25 搜索产生的 token 消耗归入
     * "知识库 hybrid 父卡片检索" 场景，便于在 LLM 用量统计面板中分类展示。
     *
     * @param searchTexts 1-N 条用于检索的文本
     * @param category 分区过滤
     * @param tier 案例层级过滤
     * @param topK 期望返回的候选 videoId 数量上限
     * @return 去重后的有序 videoId 列表
     */
    private List<String> hybridSearchMulti(List<String> searchTexts, String category, String tier, int topK) {
        if (searchTexts.size() == 1) {
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库 hybrid 父卡片检索")) {
                return knowledgeHybridStore.hybridSearch(searchTexts.get(0), category, tier, topK);
            }
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String text : searchTexts) {
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库 hybrid 父卡片检索")) {
                merged.addAll(knowledgeHybridStore.hybridSearch(text, category, tier, topK));
            }
        }
        List<String> result = new ArrayList<>(merged);
        return result.size() > topK ? new ArrayList<>(result.subList(0, topK)) : result;
    }

    /**
     * 单次向量语义检索：调用 Milvus 的 similaritySearch，按相似度降序返回 videoId 列表。
     * <p>
     * 使用 {@code var} 接住 builder 而不是显式写死 SearchRequest 的嵌套 builder 类型名，
     * 避免对 Spring AI 框架内部实现类的脆弱依赖——框架升级后类型名可能变化。
     * <p>
     * 检索结果用 {@link LlmUsageContext} 标记为"知识库父卡片向量检索"场景，
     * 将 Embedding 模型的 token 消耗归类到统一用量统计面板。
     * <p>
     * document 到 videoId 的映射规则（见 {@link #extractVideoId}）：优先读 metadata.videoId，
     * 回退到 document.getId()。索引时 embedding_id 被设为 video_id，但 metadata 才是规范路径。
     *
     * @param vectorStore 父表向量库
     * @param query 检索查询文本（已通过查询增强处理）
     * @param category 分区过滤
     * @param tier 案例层级过滤
     * @param topK 期望返回数量
     * @return 按相似度降序的去重 videoId 列表
     */
    private List<String> denseSearch(VectorStore vectorStore, String query, String category, String tier, int topK) {
        var builder = SearchRequest.builder()
                .query(query)
                .topK(topK);
        Filter.Expression filter = buildFilter(category, tier);
        if (filter != null) {
            builder.filterExpression(filter);
        }
        List<Document> documents;
        try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库父卡片向量检索")) {
            documents = vectorStore.similaritySearch(builder.build());
        }
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        // 保留相似度顺序的 videoId 列表：distinct 防止同一视频被多个相似文档命中（一个视频可能有多个 embedding 片段）
        return documents.stream()
                .map(this::extractVideoId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 按 category 和 tier 构建 Milvus 元数据过滤表达式。
     * <p>
     * 使用 {@link FilterExpressionBuilder} 而非手工拼接字符串——元数据取值可能包含
     * 特殊字符（如引号、空格），手工拼接容易产生非法的 Milvus 过滤表达式，导致检索 500。
     * FilterExpressionBuilder 自动处理转义和语法正确性。
     * <p>
     * 这些 metadata 键（videoId / tier / category / ...）由阶段 5.1c 索引流程写入父表向量文档，
     * 过滤键名必须与索引写入时完全一致，否则过滤静默失效（Milvus 不报错，只返回空结果）。
     * <p>
     * category 和 tier 任一可空：都空返回 null（无过滤）；都有用 AND 组合；只有一个用单条件。
     *
     * @param category 分区过滤值（可能为 null/空）
     * @param tier 案例层级过滤值（可能为 null/空）
     * @return Milvus 过滤表达式，无过滤条件时返回 null
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

    /**
     * 从 Milvus 返回的 Document 中提取 videoId。
     * <p>
     * 提取策略：优先读 metadata 中的 videoId 字段（索引时显式写入的规范路径），
     * 回退到 document.getId()（索引时 embedding_id 被设为 video_id，
     * 但这是框架的隐式行为，不应作为主路径依赖）。
     *
     * @param document Milvus 检索返回的单条文档
     * @return videoId 字符串；document 为 null 或无法提取时返回 null
     */
    private String extractVideoId(Document document) {
        if (document == null) {
            return null;
        }
        Object metadataVideoId = document.getMetadata() == null ? null : document.getMetadata().get("videoId");
        if (metadataVideoId != null && TextUtil.hasText(metadataVideoId.toString())) {
            return metadataVideoId.toString();
        }
        return TextUtil.trimToNull(document.getId());
    }

    /**
     * 按向量相似度顺序重排 MySQL 回查结果。
     * <p>
     * MySQL 的 WHERE video_id IN (...) 查询按主键/索引自然顺序返回，不保证与 IN 列表顺序一致。
     * 此方法将结果重新排列为与 orderedVideoIds 相同的顺序，确保相似度最高的案例排在最前面。
     *
     * @param records MySQL 回查的案例记录列表（顺序不可依赖）
     * @param orderedVideoIds 期望的 videoId 顺序（通常是向量相似度降序）
     * @return 按 orderedVideoIds 排列的记录列表，缺失的 videoId 被跳过
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
     * 多路子条目向量召回（阶段 5.2c-2 的 small 端）。
     * <p>
     * 对每条增强后的检索文本查询子表向量集合，按 videoId 聚合命中的 itemId。
     * 这是 small-to-big 策略的"small"部分——从细粒度子片段（弹幕/评论/字幕）反向找到所属的父卡片 videoId。
     * <p>
     * 返回 LinkedHashMap 而非普通 HashMap 的原因：keySet 用于 videoId 合并定序时保序，
     * values 用于后续回查子表组装证据——需要保证"先检索到的更相关"这一顺序假设。
     * <p>
     * 每个 videoId 的 itemId 去重并截到 {@link #MAX_EVIDENCE_PER_VIDEO} 条：
     * 这只是限制<b>最终展示的证据条数</b>，超出此数的命中仍贡献 videoId 登记进合并候选——
     * 一张视频被多条子片段命中说明它与查询的语义匹配度更高，作为候选的置信度更大。
     * <p>
     * 子文档的 videoId/itemId 必须从 metadata 提取而非 document.getId()：子文档的 id 是 itemId，
     * 误拿会导致 itemId 被当作 videoId（这正是子召回不能复用父侧 {@link #extractVideoId} 的根本原因）。
     *
     * @param childStore 子表向量库
     * @param searchTexts 1-N 条检索文本
     * @param category 分区过滤
     * @param tier 案例层级过滤
     * @param topK 期望返回数量
     * @return videoId -> itemId 列表的映射（保序）
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
            List<Document> documents;
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库子条目向量检索")) {
                documents = childStore.similaritySearch(builder.build());
            }
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
     * 多路主题中块向量召回（阶段 5.2c-2 的 chunk 变体）。
     * <p>
     * 中块（chunk）是介于父卡片和子片段之间的中等粒度语义单元——比如视频的一段主题段落。
     * 它的命中语义粒度比父卡片细（更能捕捉局部主题），比子条目粗（更能保持上下文完整性）。
     * <p>
     * 中块只贡献召回候选（按 videoId 上卷回父卡片），没有原始证据展示职责——最终展示的证据
     * 仍由子条目召回提供。中块文档的 id 是 chunkId，必须从 metadata 取 videoId 而非回退 getId()。
     *
     * @param chunkStore 中块向量库
     * @param searchTexts 1-N 条检索文本
     * @param category 分区过滤
     * @param tier 案例层级过滤
     * @param topK 期望返回数量
     * @return 去重后的 videoId 列表
     */
    private List<String> chunkSearchMulti(VectorStore chunkStore, List<String> searchTexts,
                                          String category, String tier, int topK) {
        Filter.Expression filter = buildFilter(category, tier);
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String text : searchTexts) {
            var builder = SearchRequest.builder().query(text).topK(topK);
            if (filter != null) {
                builder.filterExpression(filter);
            }
            List<Document> documents;
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库主题中块向量检索")) {
                documents = chunkStore.similaritySearch(builder.build());
            }
            if (documents == null) {
                continue;
            }
            for (Document document : documents) {
                String videoId = extractChunkVideoId(document);
                if (videoId != null) {
                    merged.add(videoId);
                }
            }
        }
        List<String> result = new ArrayList<>(merged);
        return result.size() > topK ? new ArrayList<>(result.subList(0, topK)) : result;
    }

    /**
     * 从主题中块的 Milvus Document 中提取 videoId（仅从 metadata，不回退 getId）。
     * <p>
     * 中块文档的 document.getId() 返回的是 chunkId（主题段落 ID），不是 videoId。
     * 如果回退到 getId() 会把 chunkId 误当成 videoId，导致回查 MySQL 父表找不到对应的案例记录。
     *
     * @param document 中块集合的检索命中文档
     * @return videoId 字符串；无法提取返回 null
     */
    private String extractChunkVideoId(Document document) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        Object value = document.getMetadata().get("videoId");
        return (value != null && TextUtil.hasText(value.toString())) ? value.toString() : null;
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
            List<KnowledgeHybridStore.HybridChildHit> hits;
            try (LlmUsageContext.UsageScope ignored = LlmUsageContext.scene("知识库 hybrid 子条目检索")) {
                hits = knowledgeHybridStore.childHybridSearch(text, category, tier, topK);
            }
            for (KnowledgeHybridStore.HybridChildHit hit : hits) {
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
     * 从子条目的 Milvus Document metadata 中提取指定字段，<b>严格不回退</b> document.getId()。
     * <p>
     * 设计约束：子文档的 document.getId() 返回的是 itemId（弹幕/评论/字幕片段 ID），
     * 而非 videoId。如果允许回退，会把 itemId 误当 videoId 注册进候选——导致一张不存在的"假卡片"出现在列表里。
     * 因此子召回相关的 videoId/itemId 提取都必须走 metadata 路径，不能复用父侧的 extractVideoId 方法。
     *
     * @param document Milvus 返回的子表文档
     * @param key metadata 中的键名（videoId 或 itemId）
     * @return 字段值字符串；无法提取返回 null
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
    private List<String> mergeVideoIds(List<String> parentVideoIds, List<String> chunkVideoIds,
                                       Set<String> childVideoIds, int topK) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(parentVideoIds);
        merged.addAll(chunkVideoIds);
        merged.addAll(childVideoIds);
        List<String> result = new ArrayList<>(merged);
        return result.size() > topK ? new ArrayList<>(result.subList(0, topK)) : result;
    }

    /**
     * 组装子条目证据片段，只对最终结果中「有子命中」的卡片挂载。
     * <p>
     * 分两步（两步间有 I/O 依赖——需要先拿到所有 itemId 才能做 MySQL IN 查询）：
     * <ol>
     *   <li>汇总最终卡片涉及的全部 itemId，一次性回查子表事实源（MySQL WHERE item_id IN (...) AND is_deleted=0）。
     *       一次 IN 查询而非逐卡逐条 SELECT——避免 N+1 查询问题。</li>
     *   <li>按 finalRecords 顺序逐卡组装证据列表，回查后该卡无有效子条目的跳过（子条目可能已被软删）。</li>
     * </ol>
     * <p>
     * SQL 关键词兜底补进来的卡片天然无子命中（它们是通过关键词 LIKE 而非向量匹配进来的），
     * 因此它们的 videoId 不在 childMap 中，组装时会被跳过。
     *
     * @param finalRecords 最终返回给前端的案例卡片列表
     * @param childMap videoId → 命中 itemId 列表的映射
     * @return 证据片段列表，与 finalRecords 中的卡片顺序一致（无证据的卡片在列表中不存在对应元素）
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

    /**
     * 将候选父卡片列表转成送 rerank 模型的文本列表。
     * <p>
     * 文本列表与 candidates 顺序严格一一对应——candidates 中第 i 个元素的 rerank 文本就是
     * 列表中第 i 个元素。rerank 返回的也是下标列表，通过此对应关系完成重排。
     *
     * @param records 候选案例卡片列表
     * @return 与 records 一一对应的卡片语义文本列表
     */
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
     * 按 rerank 返回的下标顺序重排候选列表。
     * <p>
     * rerank 模型返回的是 candidates 列表中的原始下标（index），按降序排列（index=3 排在 index=0 前面，
     * 意味着原来第 4 个候选比第 1 个候选更相关）。此方法按 order 顺序取出对应 candidates 元素。
     * <p>
     * 防御性校验：越界下标理论上已被 {@link KnowledgeRerankClient} 过滤，但这里仍做
     * index >= 0 && index < candidates.size() 的防御，防止客户端 BUG 导致 IndexOutOfBoundsException。
     * rerank 省略 top_n 参数时返回全量 candidates 的序，重排后规模不变。
     *
     * @param candidates 候选案例卡片列表（送 rerank 时的原始顺序）
     * @param order rerank 返回的下标顺序（精排后的推荐序）
     * @return 按精排顺序重排后的案例列表
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

    /**
     * 执行 SQL 关键词兜底检索（向量链路不可用或命中不足时的降级路径）。
     * <p>
     * 降级策略：不能只做整串 LIKE '%query%'——用户常输入带空格、标点的自然语句（如"如何做开场白？"），
     * 整串 LIKE 会被一个标点差异误杀。因此同时传入全量 keyword（做整串 LIKE）和切词片段（做多词 LIKE），
     * 提高兜底召回率。
     * <p>
     * 此阶段不做复杂的文本相关性打分——SQL 兜底的定位是"防止什么都搜不到"，
     * 排序交给数据库已有的 quality_score 等质量信号。真正的关键词相关性打分留在阶段 5.2d 的原生 BM25。
     *
     * @param query 原始查询文本
     * @param category 分区过滤
     * @param tier 案例层级过滤
     * @param topK 期望返回数量
     * @return 按质量分排序的案例记录列表
     */
    private List<ReferenceVideoRecord> sqlFallbackRecords(String query, String category, String tier, int topK) {
        String keyword = TextUtil.trimToNull(TextUtil.collapseWhitespace(query));
        return knowledgeReferenceVideoMapper.searchByKeyword(category, tier, keyword, extractSqlKeywords(keyword), topK);
    }

    /**
     * 从查询文本中通过正则切出最多 6 个独立关键词，用于 SQL 多词 LIKE 搜索。
     * <p>
     * 切词规则：匹配连续的中文汉字、英文字母、数字段——这些是最有检索区分度的语义单元。
     * 过滤条件：长度 < 2（单字/单字母区分度太低，容易返回大量噪音）且已出现的词去重。
     * 上限 6 个是防御——防止超长查询切出几十个词导致 SQL 膨胀。
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

    // ============================ 合并 / 截断 / 组装 ============================

    /**
     * 按 videoId 合并去重两个候选列表，primary 优先——它的顺序来自向量相似度，比 secondary 更有语义含义。
     * <p>
     * 使用 LinkedHashMap 的 putIfAbsent 而非 HashSet 去重，保留 primary 中首次出现的顺序。
     * secondary 补充 primary 中不存在的 videoId，直到达到 limit 上限。
     *
     * @param primary 优先保留的列表（向量/语义检索结果）
     * @param secondary 补充列表（SQL 关键词兜底结果）
     * @param limit 最终返回的条数上限
     * @return 合并去重后的案例记录列表
     */
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
     * tier 过滤值大写归一 + 白名单校验。
     * <p>
     * 非法值直接抛出 ResponseStatusException(400) 而非静默返回空结果——
     * 如果是静默空结果，调用方会误判为"这个层级没有数据"而浪费排查时间；
     * 直接 400 让调用方立刻知道 tier 参数不合法，与案例列表接口校验口径保持一致。
     *
     * @param tier 请求中的 tier 过滤值（可能为 null/空/大小写混合）
     * @return 大写归一化后的 tier 值；null/空返回 null（表示不过滤）
     * @throws ResponseStatusException tier 不在白名单时抛出 400
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
     * topK 入参归一化：未指定用配置默认，越界收敛到 [1, MAX_TOP_K]。
     * <p>
     * 这是二次防御——Controller 层已有 @Max(50) 校验，但 YAML 配置可能被误设为超大值，
     * 这里再做一次收敛保证最终检索数量不会撑爆内存/Milvus 连接/网络带宽。
     *
     * @param requested 请求中指定的 topK（可能为 null）
     * @return 归一化后的 topK，保证在 [1, 50] 范围内
     */
    private int resolveTopK(Integer requested) {
        int value = (requested != null) ? requested : knowledgeRagProperties.getTopK();
        if (value < 1) {
            return 1;
        }
        return Math.min(value, MAX_TOP_K);
    }

    /**
     * 查询增强策略归一化（5.2b）。
     * <p>
     * 未指定时用 application.yml 配置的默认策略（通常为 REWRITE）；
     * 指定时大写归一 + 枚举校验，非法值直接 400，口径与 {@link #normalizeTier} 一致。
     *
     * @param requested 请求中的增强策略字符串（可能为 null/空/大小写混合）
     * @return 对应的策略枚举值
     * @throws ResponseStatusException 策略不在枚举定义中时抛出 400
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
