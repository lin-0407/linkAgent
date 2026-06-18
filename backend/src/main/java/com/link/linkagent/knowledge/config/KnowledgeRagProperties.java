package com.link.linkagent.knowledge.config;

import com.link.linkagent.knowledge.model.QueryEnhanceStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 跨分区视频案例知识库 RAG 配置（阶段 5.1）。
 * <p>
 * 与 {@code creator.feedback.rag} 完全独立的一套开关：本类的 {@link #enabled} 决定知识库是否走向量链路，
 * 与反馈 RAG 互不影响。默认关闭时不连 Milvus、不调 Embedding，案例导入与列表（不依赖向量库）照常工作。
 * <p>
 * Milvus 连接参数复用与反馈侧相同的 {@code MILVUS_*} 环境变量（见 application.yml），但集合名和维度不同——
 * 知识库用专用集合 {@code creator_reference_video} @1024，与反馈集合物理隔离。
 */
@Component
@ConfigurationProperties(prefix = "knowledge.rag")
public class KnowledgeRagProperties {

    /**
     * 业务总开关，默认关闭。演示环境即使忘了配置，也不会触发 Embedding 调用和 Milvus 连接。
     */
    private boolean enabled = false;

    /**
     * 知识库专用 Milvus 集合名。与反馈集合分开，避免两套语义不同的向量混在一个集合里。
     */
    private String collectionName = "creator_reference_video";

    /**
     * 知识库专用 Milvus <b>子集合</b>名（阶段 5.2c）。与父集合 {@link #collectionName} 物理隔离：
     * 父集合存「案例卡片」大文档，子集合存「优质评论弹幕原文」小文档，两种粒度分开互不污染。
     * 与父集合复用同一个 MilvusServiceClient、同维度、同 Embedding，仅集合名不同。
     */
    private String childCollectionName = "creator_reference_video_item";

    /**
     * 知识库专用 Milvus <b>主题中块集合</b>名。主题中块位于父卡片和子条目之间，
     * 独立集合能避免标题包装 / 内容定位 / 观众反馈这类中粒度文档与父子两种粒度互相污染。
     */
    private String chunkCollectionName = "creator_reference_video_chunk";

    /**
     * 向量维度，必须与 Embedding 输出维度严格一致。默认 1024 对应 Qwen text-embedding-v4 的推荐维度；
     * 不一致会在写入时报维度冲突，所以 application.yml 里把它绑定到 LLM_EMBEDDING_DIMENSIONS 同一变量。
     */
    private int embeddingDimension = 1024;

    /**
     * 单批 Embedding 上限。默认 10：Qwen text-embedding-v3/v4 在 OpenAI 兼容模式下单批硬上限就是 10，
     * 超过会报 batch size invalid，因此不能照抄反馈侧的 25。该值留给 5.1c 的索引服务使用。
     */
    private int indexBatchSize = 10;

    /**
     * 单次重建索引默认最多写入的案例数（5.1c）。默认 200，配合接口层 @Max(1000) 与服务层硬上限，
     * 三重防御演示环境一次性把整库案例送 Embedding 的高成本。请求未显式指定 maxItems 时回落到本值。
     */
    private int maxIndexItems = 200;

    /**
     * 单次检索返回的候选案例上限（5.2a）。纯检索侧参数、与索引无关；接口层另有 @Max(50) 与服务层硬上限兜底。
     */
    private int topK = 8;

    /**
     * 向量命中数低于该值时合并 SQL 兜底（5.2a，照搬反馈侧范式），避免召回过少导致候选不足。
     */
    private int minVectorHitCount = 3;

    /**
     * 是否在构建向量库时自动建集合。默认 true：首次启用时自动按上面的维度建出专用集合；
     * 建好后可设 false 省去每次启动的 schema 检查。
     */
    private boolean initializeSchema = true;

    /**
     * Milvus 连接参数，复用 MILVUS_* 环境变量。
     */
    private final Milvus milvus = new Milvus();

    /**
     * 查询增强（5.2b）配置：默认策略 + MULTI_QUERY 条数上限。
     */
    private final QueryEnhancement queryEnhancement = new QueryEnhancement();

    /**
     * 重排序（5.2e）配置：qwen3-rerank 精排开关、模型、端点、候选池等。
     */
    private final Rerank rerank = new Rerank();

    /**
     * 原生混合检索（5.2d）配置：dense+BM25 hybrid 开关、专用集合名、BM25 文本字段长度与中文分析器。
     */
    private final Hybrid hybrid = new Hybrid();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    public String getChildCollectionName() {
        return childCollectionName;
    }

    public void setChildCollectionName(String childCollectionName) {
        this.childCollectionName = childCollectionName;
    }

    public String getChunkCollectionName() {
        return chunkCollectionName;
    }

    public void setChunkCollectionName(String chunkCollectionName) {
        this.chunkCollectionName = chunkCollectionName;
    }

    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(int embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public int getIndexBatchSize() {
        return indexBatchSize;
    }

    public void setIndexBatchSize(int indexBatchSize) {
        this.indexBatchSize = indexBatchSize;
    }

    public int getMaxIndexItems() {
        return maxIndexItems;
    }

    public void setMaxIndexItems(int maxIndexItems) {
        this.maxIndexItems = maxIndexItems;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getMinVectorHitCount() {
        return minVectorHitCount;
    }

    public void setMinVectorHitCount(int minVectorHitCount) {
        this.minVectorHitCount = minVectorHitCount;
    }

    public boolean isInitializeSchema() {
        return initializeSchema;
    }

    public void setInitializeSchema(boolean initializeSchema) {
        this.initializeSchema = initializeSchema;
    }

    public Milvus getMilvus() {
        return milvus;
    }

    public QueryEnhancement getQueryEnhancement() {
        return queryEnhancement;
    }

    public Rerank getRerank() {
        return rerank;
    }

    public Hybrid getHybrid() {
        return hybrid;
    }

    /**
     * Milvus 连接参数，字段与 spring.ai.vectorstore.milvus.client 对齐，便于复用同一套环境变量。
     */
    public static class Milvus {

        private String host = "localhost";
        private int port = 19530;
        private String username = "";
        private String password = "";
        private String databaseName = "default";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public void setDatabaseName(String databaseName) {
            this.databaseName = databaseName;
        }
    }

    /**
     * 查询增强（5.2b）配置。
     * 默认策略 REWRITE：开启 RAG 时默认即走改写增强；设为 NONE 可显式退回 5.2a 单查询行为。
     */
    public static class QueryEnhancement {

        /** 默认查询增强策略。默认 REWRITE（单路、最稳）；NONE 显式关闭。 */
        private QueryEnhanceStrategy strategy = QueryEnhanceStrategy.REWRITE;

        /** MULTI_QUERY 扩展查询条数上限，二次防御检索次数放大。 */
        private int multiQueryCount = 3;

        public QueryEnhanceStrategy getStrategy() {
            return strategy;
        }

        public void setStrategy(QueryEnhanceStrategy strategy) {
            this.strategy = strategy;
        }

        public int getMultiQueryCount() {
            return multiQueryCount;
        }

        public void setMultiQueryCount(int multiQueryCount) {
            this.multiQueryCount = multiQueryCount;
        }
    }

    /**
     * 重排序（5.2e）配置。
     * 默认<b>关</b>：rerank 是每次检索额外一次外部调用（成本 + 延迟），opt-in 才开（与 RAG 总开关默认关同思路）。
     */
    public static class Rerank {

        /** 是否启用 qwen3-rerank 精排。默认 false。 */
        private boolean enabled = false;

        /** 重排模型 id。默认 qwen3-rerank（老的 gte-rerank 已于 2026-05-30 下线）。 */
        private String model = "qwen3-rerank";

        /**
         * qwen3-rerank 的 OpenAI 兼容端点 base-url。注意是 {@code compatible-api}（rerank 专用），
         * 不是 chat 用的 {@code compatible-mode}——两者不同，用错会 404 / 报错。
         */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-api/v1";

        /** 重排服务 API Key。默认在 application.yml 绑定到 ${LLM_API_KEY}（复用同一个 DashScope Key）。 */
        private String apiKey = "";

        /**
         * 开启 rerank 时先召回的更宽候选池上限（retrieve-wide → rerank → 截 topK）。
         * 精排只重排不扩召回，候选越宽精排空间越大；上限受检索层 MAX_TOP_K 二次收敛。
         */
        private int candidatePoolSize = 20;

        /** 调用超时（毫秒），防慢响应拖垮 /search 同步链路。 */
        private int timeoutMs = 10000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getCandidatePoolSize() {
            return candidatePoolSize;
        }

        public void setCandidatePoolSize(int candidatePoolSize) {
            this.candidatePoolSize = candidatePoolSize;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /**
     * 原生混合检索（5.2d）配置。
     * 默认<b>关</b>：开启需 Milvus 服务端 ≥2.5（BM25 是服务端能力）+ 自建 schema 集合 + 重灌；与 RAG 总开关一起双门控。
     */
    public static class Hybrid {

        /** 是否启用原生 dense+BM25 hybrid。默认 false（双开关：还需 knowledge.rag.enabled=true）。 */
        private boolean enabled = false;

        /** hybrid 专用集合名（自建 schema，与 Spring AI 那套父/子集合物理隔离）。 */
        private String collectionName = "creator_reference_video_hybrid";

        /** 子条目 hybrid 集合名（5.2d-3）：子条目原文的 dense+BM25 hybrid 集合，与父 hybrid 集合物理隔离。 */
        private String childCollectionName = "creator_reference_video_item_hybrid";

        /** BM25 text 字段 VarChar 上限。默认取 Milvus 上限 65535，避免长案例卡片插入越界（中文多字节）。 */
        private int textMaxLength = 65535;

        /** text 字段分析器类型。默认 chinese：中文无空格，standard 分析器分词差会劣化 BM25 召回。 */
        private String analyzerType = "chinese";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCollectionName() {
            return collectionName;
        }

        public void setCollectionName(String collectionName) {
            this.collectionName = collectionName;
        }

        public String getChildCollectionName() {
            return childCollectionName;
        }

        public void setChildCollectionName(String childCollectionName) {
            this.childCollectionName = childCollectionName;
        }

        public int getTextMaxLength() {
            return textMaxLength;
        }

        public void setTextMaxLength(int textMaxLength) {
            this.textMaxLength = textMaxLength;
        }

        public String getAnalyzerType() {
            return analyzerType;
        }

        public void setAnalyzerType(String analyzerType) {
            this.analyzerType = analyzerType;
        }
    }
}
