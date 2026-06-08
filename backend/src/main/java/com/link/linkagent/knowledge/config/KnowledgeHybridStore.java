package com.link.linkagent.knowledge.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.link.linkagent.util.TextUtil;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识库原生混合检索存储（阶段 5.2d）：用 Milvus v2 客户端 + <b>自建 schema</b> 承载 dense + BM25 sparse 原生 hybrid。
 * <p>
 * <b>为何另起一个 Store（不复用 {@link KnowledgeVectorStore}）</b>：5.1c/5.2a-c 的 {@link KnowledgeVectorStore} 用
 * Spring AI {@code MilvusVectorStore}——它按<b>固定 schema</b> 建集合、且自动做 embedding，<b>没有</b> sparse 字段与 BM25 Function，
 * 无法做原生 hybrid。原生 hybrid 必须自建 schema（{@code VarChar(enableAnalyzer)} 原文 + {@code SparseFloatVector} +
 * BM25 {@code Function} + dense {@code FloatVector}）并用 raw {@code MilvusClientV2}，故单列本组件。
 * <p>
 * <b>隔离 + 双层门控 + 独立降级</b>：延续 {@link KnowledgeVectorStore} 的隔离哲学（内部私有持 client，不暴露成 Bean）。
 * 由 {@code knowledge.rag.enabled} 且 {@code knowledge.rag.hybrid.enabled} <b>双开关</b>门控：默认关时一切不变、不连 Milvus，
 * 5.2a-c/e 的 Spring AI 老路径照常工作（<b>零回归</b>）。建库/连接失败只降级（ready=false），不拖垮后端。
 * <p>
 * <b>API 已对 milvus-sdk-java 2.5.8 字节码核准</b>（官方文档被墙，javap 核实）：见 docs/develop/阶段5.2 §18.1。
 */
@Component
public class KnowledgeHybridStore {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeHybridStore.class);

    /** 集合字段名（与索引/插入/检索共用，避免散落字符串）。 */
    public static final String FIELD_VIDEO_ID = "video_id";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_SPARSE = "sparse";
    public static final String FIELD_DENSE = "dense";
    public static final String FIELD_CATEGORY = "category";
    public static final String FIELD_TIER = "tier";
    /** 子集合主键字段名（5.2d-3）：子条目 hybrid 集合 PK=item_id，父集合 PK=video_id。 */
    public static final String FIELD_ITEM_ID = "item_id";
    /** BM25 Function 名（把 text 原文转成 sparse 稀疏向量，服务端自动生成、不手插）。 */
    private static final String BM25_FUNCTION_NAME = "text_bm25";
    /** RRF 融合平滑系数 k：业界常用 60，无偏好地均衡 dense 与 BM25 两路名次。 */
    private static final int RRF_K = 60;

    private final KnowledgeRagProperties properties;
    /** 用 ObjectProvider 注入：embedding 关闭时没有 EmbeddingModel Bean，硬注入会导致启动失败。 */
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    /** 内部私有、非 Spring Bean —— 隔离的关键。 */
    private MilvusClientV2 client;
    /** dense 向量由这里手动算（raw client 不像 Spring AI MilvusVectorStore 那样自动 embedding）。 */
    private EmbeddingModel embeddingModel;
    private volatile boolean ready = false;

    public KnowledgeHybridStore(KnowledgeRagProperties properties,
                                ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.properties = properties;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    /**
     * 双开关门控：仅当 RAG 总开关 + hybrid 子开关都开、且有 EmbeddingModel 时才连 Milvus；否则保持降级、不抛异常。
     * 连接失败也只降级（ready=false），保证 hybrid 没配好时后端照常启动、老检索路径不受影响。
     */
    @PostConstruct
    public void init() {
        if (!properties.isEnabled() || !properties.getHybrid().isEnabled()) {
            log.info("知识库原生 hybrid 默认关闭（knowledge.rag.hybrid.enabled=false），跳过 Milvus v2 连接；其余检索路径正常。");
            return;
        }
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        if (model == null) {
            log.warn("hybrid.enabled=true 但未配置 EmbeddingModel（EMBEDDING_MODEL_TYPE=none），hybrid 不可用，降级。");
            return;
        }
        try {
            this.client = buildClient();
            this.embeddingModel = model;
            this.ready = true;
            log.info("知识库原生 hybrid 向量库就绪：collection={}, dimension={}。",
                    properties.getHybrid().getCollectionName(), properties.getEmbeddingDimension());
        } catch (Exception exception) {
            this.ready = false;
            log.error("知识库 hybrid 向量库初始化失败，已降级（不影响其余检索路径）。请检查 Milvus v2 连接。", exception);
        }
    }

    private MilvusClientV2 buildClient() {
        KnowledgeRagProperties.Milvus milvus = properties.getMilvus();
        // v2 用 uri（http://host:port）而非 v1 的 host/port 分开；复用同一套 MILVUS_* 连接参数。
        // 两分支各自单链 build()，避开 @SuperBuilder 通配符中间变量的类型推断坑。
        String uri = "http://" + milvus.getHost() + ":" + milvus.getPort();
        ConnectConfig config;
        if (StringUtils.hasText(milvus.getUsername())) {
            // 配了用户名才带认证，避免空账号触发认证失败（同 KnowledgeVectorStore 的处理）。
            config = ConnectConfig.builder().uri(uri).dbName(milvus.getDatabaseName())
                    .username(milvus.getUsername()).password(milvus.getPassword()).build();
        } else {
            config = ConnectConfig.builder().uri(uri).dbName(milvus.getDatabaseName()).build();
        }
        return new MilvusClientV2(config);
    }

    public boolean isReady() {
        return ready;
    }

    // ============================ 集合生命周期（迁移 / 重灌用） ============================

    /**
     * 重建 hybrid 集合：存在则先 drop，再按自建 schema 建集合 + 建 dense/sparse 索引 + load。
     * 迁移与重灌时调用——MySQL 是事实源，集合可反复重建、无数据损失。
     */
    public void recreateCollection() {
        ensureReady();
        CreateCollectionReq.CollectionSchema schema = client.createSchema();
        // 父集合主键用 video_id：与父表案例天然一一对应，hybridSearch 命中后直接拿 videoId 回查父表（同 5.2a 范式）。
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_VIDEO_ID).dataType(DataType.VarChar)
                .isPrimaryKey(true).autoID(false).maxLength(64).build());
        addCommonHybridFields(schema);
        createAndLoad(properties.getHybrid().getCollectionName(), schema);
    }

    /**
     * 重建子集合 hybrid（5.2d-3）：PK=item_id + 普通 video_id 字段（small-to-big 上卷用）+ 公共 text/sparse/dense/过滤字段。
     * 与父集合并行、物理隔离；text 是子条目原文（评论/弹幕），同时喂 BM25 与 dense。
     */
    public void recreateChildCollection() {
        ensureReady();
        CreateCollectionReq.CollectionSchema schema = client.createSchema();
        // 子集合主键用 item_id：与子条目一一对应，命中后用 item_id 回查 MySQL 取证据（同 5.2c-2 范式）。
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_ITEM_ID).dataType(DataType.VarChar)
                .isPrimaryKey(true).autoID(false).maxLength(64).build());
        // video_id 普通字段（非主键）：small-to-big 把命中子条目上卷到所属父视频；检索时走 outFields 取，不能用 getId()。
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_VIDEO_ID).dataType(DataType.VarChar).maxLength(64).build());
        addCommonHybridFields(schema);
        createAndLoad(properties.getHybrid().getChildCollectionName(), schema);
    }

    /**
     * 追加父/子集合共有字段 + BM25 Function：text（BM25 输入原文，开中文 analyzer）/ sparse（BM25 输出，服务端生成）
     * / dense（手动算的语义向量）/ category·tier（过滤）。父子两集合的差异只在主键与 video_id 字段，公共部分这里统一。
     */
    private void addCommonHybridFields(CreateCollectionReq.CollectionSchema schema) {
        // text：BM25 输入原文，必须开 analyzer；中文要中文分析器，否则无空格中文分词差、BM25 召回劣化。
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_TEXT).dataType(DataType.VarChar)
                .maxLength(properties.getHybrid().getTextMaxLength())
                .enableAnalyzer(true)
                // 显式 Map<String,Object>：Map.of(String,String) 不能直接喂 analyzerParams(Map<String,Object>)（泛型不变）。
                .analyzerParams(Map.<String, Object>of("type", properties.getHybrid().getAnalyzerType()))
                .build());
        // sparse：BM25 Function 的输出字段，插入时不写、服务端自动生成。
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_SPARSE).dataType(DataType.SparseFloatVector).build());
        // dense：手动算的语义向量，维度须与 Embedding 输出一致。
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_DENSE).dataType(DataType.FloatVector)
                .dimension(properties.getEmbeddingDimension()).build());
        // category/tier：供 hybridSearch 的 expr 过滤（与 5.2a 的 category/tier 元数据过滤同义）。
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CATEGORY).dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_TIER).dataType(DataType.VarChar).maxLength(16).build());
        // BM25 Function：把 text 原文转 sparse；这是 Milvus 2.5+ 服务端能力。
        schema.addFunction(CreateCollectionReq.Function.builder()
                .name(BM25_FUNCTION_NAME).functionType(FunctionType.BM25)
                .inputFieldNames(List.of(FIELD_TEXT)).outputFieldNames(List.of(FIELD_SPARSE)).build());
    }

    /**
     * drop 旧集合（存在则删）→ 按 schema 建集合 + dense/sparse 索引 → 显式 load。父子集合共用此收尾流程。
     */
    private void createAndLoad(String name, CreateCollectionReq.CollectionSchema schema) {
        if (Boolean.TRUE.equals(client.hasCollection(HasCollectionReq.builder().collectionName(name).build()))) {
            client.dropCollection(DropCollectionReq.builder().collectionName(name).build());
        }
        List<IndexParam> indexParams = new ArrayList<>();
        indexParams.add(IndexParam.builder()
                .fieldName(FIELD_DENSE).indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE).build());
        indexParams.add(IndexParam.builder()
                .fieldName(FIELD_SPARSE).indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25).build());
        client.createCollection(CreateCollectionReq.builder()
                .collectionName(name).collectionSchema(schema).indexParams(indexParams).build());
        // 显式 load：建集合带 indexParams 通常会自动 load，这里再确保一次（已 load 时幂等）。
        client.loadCollection(LoadCollectionReq.builder().collectionName(name).build());
    }

    // ============================ 写入（索引） ============================

    /**
     * 批量插入父卡片：写 text 原文 + 手动算的 dense + category/tier；<b>sparse 不写</b>（BM25 Function 服务端生成）。
     * 调用方按 Embedding 单批上限（默认 10）分批，故这里一次 embed 整批、再组 row 插入。
     */
    public int insertParentDocs(List<HybridParentDoc> docs) {
        ensureReady();
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        List<String> texts = docs.stream().map(HybridParentDoc::text).toList();
        // 一次性批量 embed（EmbeddingModel.embed(List) 默认方法，已对字节码核准）。
        List<float[]> vectors = embeddingModel.embed(texts);
        List<JsonObject> rows = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            HybridParentDoc doc = docs.get(i);
            JsonObject row = new JsonObject();
            row.addProperty(FIELD_VIDEO_ID, doc.videoId());
            row.addProperty(FIELD_TEXT, doc.text());
            // VarChar 非主键字段不能缺；用空串占位，避免 null 触发字段缺失。
            row.addProperty(FIELD_CATEGORY, doc.category() == null ? "" : doc.category());
            row.addProperty(FIELD_TIER, doc.tier() == null ? "" : doc.tier());
            JsonArray dense = new JsonArray();
            for (float value : vectors.get(i)) {
                dense.add(value);
            }
            row.add(FIELD_DENSE, dense);
            rows.add(row);
        }
        InsertResp resp = client.insert(InsertReq.builder()
                .collectionName(properties.getHybrid().getCollectionName()).data(rows).build());
        return (int) resp.getInsertCnt();
    }

    /**
     * 批量插入子条目（5.2d-3）：写 item_id（PK）+ video_id + text 原文 + 手动算 dense + category/tier；
     * <b>sparse 不写</b>（BM25 Function 服务端生成）。与 {@link #insertParentDocs} 仅多一个 item_id 主键与 video_id 字段。
     */
    public int insertChildDocs(List<HybridChildDoc> docs) {
        ensureReady();
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        List<String> texts = docs.stream().map(HybridChildDoc::text).toList();
        List<float[]> vectors = embeddingModel.embed(texts);
        List<JsonObject> rows = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            HybridChildDoc doc = docs.get(i);
            JsonObject row = new JsonObject();
            row.addProperty(FIELD_ITEM_ID, doc.itemId());
            row.addProperty(FIELD_VIDEO_ID, doc.videoId());
            row.addProperty(FIELD_TEXT, doc.text());
            // VarChar 非主键字段不能缺；用空串占位，避免 null 触发字段缺失。
            row.addProperty(FIELD_CATEGORY, doc.category() == null ? "" : doc.category());
            row.addProperty(FIELD_TIER, doc.tier() == null ? "" : doc.tier());
            JsonArray dense = new JsonArray();
            for (float value : vectors.get(i)) {
                dense.add(value);
            }
            row.add(FIELD_DENSE, dense);
            rows.add(row);
        }
        InsertResp resp = client.insert(InsertReq.builder()
                .collectionName(properties.getHybrid().getChildCollectionName()).data(rows).build());
        return (int) resp.getInsertCnt();
    }

    // ============================ 原生 hybrid 检索（5.2d-2 接入 search 用） ============================

    /**
     * dense + BM25 sparse 原生混合检索 + RRF 融合，返回按融合名次排序的 videoId。
     * dense 路用手动算的查询向量（{@link FloatVec}），BM25 路直接交原始 query 文本（{@link EmbeddedText}）由服务端转稀疏。
     */
    public List<String> hybridSearch(String query, String category, String tier, int topK) {
        ensureReady();
        float[] denseQuery = embeddingModel.embed(query);
        String expr = buildFilterExpr(category, tier);

        List<AnnSearchReq> requests = new ArrayList<>();
        // dense 路用手动算的查询向量；BM25 路直接交原始 query 文本，服务端转稀疏向量。
        requests.add(annSearchReq(FIELD_DENSE, new FloatVec(denseQuery), topK, expr));
        requests.add(annSearchReq(FIELD_SPARSE, new EmbeddedText(query), topK, expr));

        SearchResp resp = client.hybridSearch(HybridSearchReq.builder()
                .collectionName(properties.getHybrid().getCollectionName())
                .searchRequests(requests)
                .ranker(new RRFRanker(RRF_K))
                .topK(topK)
                .outFields(List.of(FIELD_VIDEO_ID))
                .consistencyLevel(ConsistencyLevel.BOUNDED)
                .build());

        List<String> videoIds = new ArrayList<>();
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
        if (results != null && !results.isEmpty()) {
            for (SearchResp.SearchResult result : results.get(0)) {
                // 主键即 video_id；getId() 返回 PK。
                Object id = result.getId();
                if (id != null && TextUtil.hasText(id.toString())) {
                    videoIds.add(id.toString());
                }
            }
        }
        return videoIds;
    }

    /**
     * 子集合 dense+BM25 原生混合检索 + RRF（5.2d-3）：返回按融合名次排序的 (videoId, itemId) 命中对。
     * item_id 是 PK 走 {@link SearchResp.SearchResult#getId()}；video_id 非主键，须 {@code outFields} 带出、从
     * {@link SearchResp.SearchResult#getEntity()} 取（已对 2.5.8 字节码核准 getEntity():Map&lt;String,Object&gt;）。
     * 供 small-to-big 子召回把命中子条目上卷回父视频。
     */
    public List<HybridChildHit> childHybridSearch(String query, String category, String tier, int topK) {
        ensureReady();
        float[] denseQuery = embeddingModel.embed(query);
        String expr = buildFilterExpr(category, tier);

        List<AnnSearchReq> requests = new ArrayList<>();
        requests.add(annSearchReq(FIELD_DENSE, new FloatVec(denseQuery), topK, expr));
        requests.add(annSearchReq(FIELD_SPARSE, new EmbeddedText(query), topK, expr));

        SearchResp resp = client.hybridSearch(HybridSearchReq.builder()
                .collectionName(properties.getHybrid().getChildCollectionName())
                .searchRequests(requests)
                .ranker(new RRFRanker(RRF_K))
                .topK(topK)
                // item_id 走 getId()；video_id 非主键，必须 outFields 带出才会进 entity。
                .outFields(List.of(FIELD_VIDEO_ID))
                .consistencyLevel(ConsistencyLevel.BOUNDED)
                .build());

        List<HybridChildHit> hits = new ArrayList<>();
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
        if (results != null && !results.isEmpty()) {
            for (SearchResp.SearchResult result : results.get(0)) {
                Object itemId = result.getId();
                Object videoId = (result.getEntity() == null) ? null : result.getEntity().get(FIELD_VIDEO_ID);
                if (itemId != null && TextUtil.hasText(itemId.toString())
                        && videoId != null && TextUtil.hasText(videoId.toString())) {
                    hits.add(new HybridChildHit(videoId.toString(), itemId.toString()));
                }
            }
        }
        return hits;
    }

    /**
     * 拼 Milvus 布尔过滤表达式（category/tier 任一可空）。转义双引号防取值破坏表达式。
     */
    private String buildFilterExpr(String category, String tier) {
        List<String> parts = new ArrayList<>();
        if (TextUtil.hasText(category)) {
            parts.add(FIELD_CATEGORY + " == \"" + category.replace("\"", "\\\"") + "\"");
        }
        if (TextUtil.hasText(tier)) {
            parts.add(FIELD_TIER + " == \"" + tier.replace("\"", "\\\"") + "\"");
        }
        return parts.isEmpty() ? null : String.join(" && ", parts);
    }

    /**
     * 构建单路 ANN 检索请求（dense 或 sparse）。用 var + 逐句 set 避开 @SuperBuilder 通配符链式的类型推断坑；
     * expr 非空才加过滤，避免传 expr("") 改变语义。
     */
    private AnnSearchReq annSearchReq(String fieldName, BaseVector vector, int topK, String expr) {
        var builder = AnnSearchReq.builder()
                .vectorFieldName(fieldName)
                .vectors(List.of(vector))
                .topK(topK)
                .params("{}");
        if (expr != null) {
            builder.expr(expr);
        }
        return builder.build();
    }

    private void ensureReady() {
        if (!ready) {
            throw new IllegalStateException("知识库 hybrid 向量库未就绪");
        }
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception exception) {
                log.warn("关闭知识库 hybrid Milvus v2 客户端时出现异常，已忽略。", exception);
            }
        }
    }

    /**
     * 插入父卡片的窄输入：videoId（主键）+ text（BM25 原文 & dense 嵌入源）+ category/tier（过滤）。
     * sparse/dense 不在此对象——dense 由 Store 手动算、sparse 由服务端 Function 生成。
     */
    public record HybridParentDoc(String videoId, String text, String category, String tier) {
    }

    /**
     * 插入子条目的窄输入（5.2d-3）：itemId（PK）+ videoId（上卷父视频）+ text（BM25 原文 &amp; dense 源）+ category/tier（过滤）。
     */
    public record HybridChildDoc(String itemId, String videoId, String text, String category, String tier) {
    }

    /**
     * 子集合 hybrid 命中（5.2d-3）：videoId（small-to-big 上卷父视频）+ itemId（回查 MySQL 取证据）。
     */
    public record HybridChildHit(String videoId, String itemId) {
    }
}
