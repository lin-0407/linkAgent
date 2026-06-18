package com.link.linkagent.knowledge.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 知识库专用向量库（阶段 5.1a：隔离骨架）。
 * <p>
 * <b>隔离设计（本类存在的根本理由）</b>：本类是 {@code KnowledgeVectorStore} 类型的 Spring Bean，
 * 内部私有持有一个 {@link MilvusVectorStore}，但<b>绝不</b>把它暴露成 {@code VectorStore} 类型的 Spring Bean。
 * 因为反馈 RAG 通过 {@code ObjectProvider<VectorStore>.getIfAvailable()} 注入向量库，一旦容器里出现第二个
 * {@code VectorStore} Bean，{@code getIfAvailable()} 会抛 {@code NoUniqueBeanDefinitionException}。
 * 把知识库向量库藏在普通 Bean 内部，就从根上消除了这种多 Bean 歧义——这正是 §2.4 选择「自行构建、内部持有」的原因。
 * <p>
 * <b>默认关 + 优雅降级</b>：{@code knowledge.rag.enabled} 默认 false，或没有 EmbeddingModel、或建库失败时，
 * 本类保持 {@code ready=false}、不连 Milvus；导入与列表（5.1a）不依赖向量库照常工作，检索（5.2）此时走 SQL 兜底。
 */
@Component
public class KnowledgeVectorStore {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorStore.class);

    private final KnowledgeRagProperties properties;
    /** 用 ObjectProvider 注入：embedding 关闭（EMBEDDING_MODEL_TYPE=none）时没有 EmbeddingModel Bean，硬注入会导致启动失败。 */
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    /** 内部持有、非 Spring Bean —— 隔离的关键所在。 */
    private MilvusServiceClient milvusClient;
    private MilvusVectorStore vectorStore;
    private volatile boolean ready = false;

    /**
     * 子集合（5.2c）向量库与就绪位，与父集合<b>独立</b>：复用同一个 {@link #milvusClient}，但集合名、就绪状态分开。
     * 独立就绪位是「零回归」的关键——子集合建库失败只降级子召回，父集合 {@link #ready} 不受影响、5.2a/b 检索照常。
     */
    private MilvusVectorStore childVectorStore;
    private volatile boolean childReady = false;

    /**
     * 主题中块集合向量库与就绪位。中块是父卡片和子原文之间的第三种粒度，
     * 独立集合能让它单独降级：中块建库失败时，父卡片和子条目检索仍然照常工作。
     */
    private MilvusVectorStore chunkVectorStore;
    private volatile boolean chunkReady = false;

    public KnowledgeVectorStore(KnowledgeRagProperties properties,
                                ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.properties = properties;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    /**
     * 启动时按「两层判断」决定是否构建向量库：业务开关 enabled + 基础设施 EmbeddingModel 是否存在。
     * 任一不满足就保持降级状态，不抛异常，保证 RAG 关闭时后端照常启动。
     */
    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("知识库 RAG 默认关闭（knowledge.rag.enabled=false），跳过 Milvus 连接；案例导入与列表正常可用。");
            return;
        }
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            log.warn("knowledge.rag.enabled=true 但未配置 EmbeddingModel（EMBEDDING_MODEL_TYPE=none），知识库降级为 SQL，向量索引不可用。");
            return;
        }
        try {
            this.milvusClient = buildMilvusClient();
            MilvusVectorStore store = MilvusVectorStore.builder(milvusClient, embeddingModel)
                    .collectionName(properties.getCollectionName())
                    .databaseName(properties.getMilvus().getDatabaseName())
                    .embeddingDimension(properties.getEmbeddingDimension())
                    .indexType(IndexType.IVF_FLAT)
                    .metricType(MetricType.COSINE)
                    .initializeSchema(properties.isInitializeSchema())
                    .build();
            // 关键坑：本 MilvusVectorStore 不是 Spring 托管 Bean，容器不会替它回调 afterPropertiesSet，
            // 必须手动调用一次，集合 schema 才会真正建出来；否则首次写入会因为集合不存在而失败。
            store.afterPropertiesSet();
            this.vectorStore = store;
            this.ready = true;
            log.info("知识库专用向量库就绪：collection={}, dimension={}, indexBatchSize={}。",
                    properties.getCollectionName(), properties.getEmbeddingDimension(), properties.getIndexBatchSize());
        } catch (Exception exception) {
            // 建库失败（Milvus 没起、维度冲突等）不应拖垮整个后端：降级为 SQL，案例的存储与列表仍然可用。
            this.ready = false;
            log.error("知识库向量库初始化失败，已降级为 SQL（导入与列表不受影响）。请检查 Milvus 连接与维度是否匹配。", exception);
        }

        // 子集合（5.2c）：与父集合复用同一个 MilvusServiceClient、同 Embedding、同维度、同索引/度量，仅集合名不同。
        // 放在父集合之后、用独立 try + 独立就绪位（childReady）：子集合建库失败只 log.warn 并让子召回不可用，
        // 绝不回滚父集合的 ready —— 这正是「5.2c 对 5.2a/b 零回归」的落点。父集合连 client 都没建出来时跳过。
        if (this.milvusClient != null) {
            try {
                MilvusVectorStore childStore = MilvusVectorStore.builder(milvusClient, embeddingModel)
                        .collectionName(properties.getChildCollectionName())
                        .databaseName(properties.getMilvus().getDatabaseName())
                        .embeddingDimension(properties.getEmbeddingDimension())
                        .indexType(IndexType.IVF_FLAT)
                        .metricType(MetricType.COSINE)
                        .initializeSchema(properties.isInitializeSchema())
                        .build();
                // 同父集合：非 Spring 托管 Bean，必须手动 afterPropertiesSet 才会真正建出子集合 schema。
                childStore.afterPropertiesSet();
                this.childVectorStore = childStore;
                this.childReady = true;
                log.info("知识库子条目向量库就绪：childCollection={}, dimension={}。",
                        properties.getChildCollectionName(), properties.getEmbeddingDimension());
            } catch (Exception exception) {
                this.childReady = false;
                log.warn("知识库子条目向量库初始化失败，子召回不可用（父集合检索不受影响）。请检查子集合维度是否匹配。", exception);
            }

            try {
                MilvusVectorStore chunkStore = MilvusVectorStore.builder(milvusClient, embeddingModel)
                        .collectionName(properties.getChunkCollectionName())
                        .databaseName(properties.getMilvus().getDatabaseName())
                        .embeddingDimension(properties.getEmbeddingDimension())
                        .indexType(IndexType.IVF_FLAT)
                        .metricType(MetricType.COSINE)
                        .initializeSchema(properties.isInitializeSchema())
                        .build();
                // 同父 / 子集合：非 Spring 托管 Bean，必须手动 afterPropertiesSet 才会真正建出中块集合 schema。
                chunkStore.afterPropertiesSet();
                this.chunkVectorStore = chunkStore;
                this.chunkReady = true;
                log.info("知识库主题中块向量库就绪：chunkCollection={}, dimension={}。",
                        properties.getChunkCollectionName(), properties.getEmbeddingDimension());
            } catch (Exception exception) {
                this.chunkReady = false;
                log.warn("知识库主题中块向量库初始化失败，中块召回不可用（父/子集合检索不受影响）。请检查中块集合维度是否匹配。", exception);
            }
        }
    }

    private MilvusServiceClient buildMilvusClient() {
        KnowledgeRagProperties.Milvus milvus = properties.getMilvus();
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(milvus.getHost())
                .withPort(milvus.getPort())
                .withDatabaseName(milvus.getDatabaseName());
        // 用户名为空表示免认证的本地 Milvus；只有配了用户名才带认证，避免空账号触发认证失败。
        if (StringUtils.hasText(milvus.getUsername())) {
            builder.withAuthorization(milvus.getUsername(), milvus.getPassword());
        }
        return new MilvusServiceClient(builder.build());
    }

    /**
     * 向量库是否就绪。5.1c 索引、5.2 检索都先查这里，未就绪则走 SQL 兜底。
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * 把内部向量库交给索引/检索服务使用；未就绪时返回空，调用方据此降级。
     * 返回 {@link VectorStore} 接口类型只是给调用方用，并不改变「它不是 Spring Bean」这一隔离事实。
     */
    public Optional<VectorStore> getVectorStore() {
        return Optional.ofNullable(vectorStore);
    }

    /**
     * 子条目向量库（5.2c）是否就绪。与父 {@link #isReady()} <b>独立</b>：子建库失败时这里为 false、父仍可为 true。
     * 5.2c-1 子条目索引、5.2c-2 子召回都先查这里，未就绪则不碰子集合。
     */
    public boolean isChildReady() {
        return childReady;
    }

    /**
     * 把子条目向量库交给索引/检索服务使用；未就绪时返回空，调用方据此降级（不影响父集合检索）。
     */
    public Optional<VectorStore> getChildVectorStore() {
        return Optional.ofNullable(childVectorStore);
    }

    /**
     * 主题中块向量库是否就绪。中块召回是增强层，未就绪时检索自动退回父卡片 + 子条目召回。
     */
    public boolean isChunkReady() {
        return chunkReady;
    }

    /**
     * 把主题中块向量库交给索引 / 检索服务使用；未就绪时返回空，调用方据此降级。
     */
    public Optional<VectorStore> getChunkVectorStore() {
        return Optional.ofNullable(chunkVectorStore);
    }

    @PreDestroy
    public void close() {
        if (milvusClient != null) {
            try {
                milvusClient.close();
            } catch (Exception exception) {
                log.warn("关闭知识库 Milvus 客户端时出现异常，已忽略。", exception);
            }
        }
    }
}
