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
}
