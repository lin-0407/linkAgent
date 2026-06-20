# 第 5 章 RAG（检索增强生成）

> 让 Agent 基于公司内部知识库回答，不靠微调，靠检索。版本基线：Spring AI **1.1.4**。

## 核心映射表

| | 第 4 章 记忆 | 第 5 章 RAG |
|---|---|---|
| 存什么 | 对话历史（`Message`） | 知识文档（`Document`） |
| 存哪里 | `ChatMemoryRepository` | `VectorStore` |
| 怎么检索 | 按 `conversationId` 拉全量历史 | 按语义相似度拉 top-K 片段 |
| 切面名 | `MessageChatMemoryAdvisor` | `RetrievalAugmentationAdvisor` |

两套机制挂在同一条 Advisor 链上，order 调好就能叠加。

---

## 5.1 RAG 本质与选型

**根因**：LLM 训练数据有截止日期，且永远不包含公司内部政策文档、FAQ、员工手册等。模型会自信地编造听起来合理的答案（幻觉）。

**两条出路**：

| 路线 | 做法 | 代价 | 适用场景 |
|---|---|---|---|
| 微调（Fine-tuning） | 用文档重训练/微调模型 | 算力大、更新成本高 | 知识固定、量极大 |
| **RAG（检索增强）** | 每次回答前检索文档库，片段塞进 prompt | 低、更新随时生效 | 绝大多数企业场景 |

> **RAG = 调 LLM 前先用用户问题检索文档库，把找到的片段塞进 prompt，让模型基于这些真实内容回答。**

---

## 5.2 Index 与 Query 两阶段

**Index 阶段**（一次性/批量入库）：原始文档 → TextSplitter 切 chunk → EmbeddingModel 向量化（`float[]`）→ VectorStore 存储。

切块原因：把一整本手册整块向量化毫无意义。切成 500~800 token 的小块，每块对应一个语义单元，检索更准，塞 prompt 的 token 也更少。

向量：文本转高维 float 数组（OpenAI `text-embedding-3-small` = 1536 维，`3-large` = 3072 维）。语义相近的文本在向量空间中距离近（余弦相似度），"退款政策"和"退款规定"字面不同但向量距离近。

**Query 阶段**（每次用户提问）：用户问题向量化 → VectorStore 余弦相似度检索 → RetrievalAugmentationAdvisor 在 before 钩子把 top-K 片段拼进 prompt → LLM 回答。业务代码不动。

---

## 5.3 V15：最简 RAG

| 组件 | V15 选型 | 生产选型 |
|---|---|---|
| 文档存储 | `SimpleVectorStore`（内存，零配置） | PGVector / Qdrant |
| 文档来源 | 硬编码字符串 | ETL Pipeline |
| RAG 切面 | `QuestionAnswerAdvisor` | `RetrievalAugmentationAdvisor` |

零新依赖 — `SimpleVectorStore` 和 `QuestionAnswerAdvisor` 都在 `spring-ai-core` 内。

**配置**：

```yaml
spring.ai.openai.embedding.options.model: text-embedding-3-small  # 1536维，性价比最高
```

**核心代码**：

```java
@Component
public class V15RagAgent implements CommandLineRunner {
    private final ChatClient chatClient;

    public V15RagAgent(ChatClient.Builder builder, EmbeddingModel embeddingModel) {
        VectorStore vs = buildVectorStore(embeddingModel);
        this.chatClient = builder
                .defaultAdvisors(new QuestionAnswerAdvisor(vs))
                .build();
    }

    public String ask(String question) {
        return chatClient.prompt().user(question).call().content();
    }

    private VectorStore buildVectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore vs = SimpleVectorStore.builder(embeddingModel).build();
        vs.add(List.of(
            new Document("退款到账时限：审核通过后平台在 3 个工作日内原路退款，" +
                "受银行处理时效影响，到账时间最长不超过 7 个自然日。",
                Map.of("source", "退款政策 v2.1", "type", "policy")),
            new Document("退款有效期：收到商品后 7 天内可无理由退款；" +
                "7 天至 30 天须提供质量问题证明；超过 30 天不支持退款。",
                Map.of("source", "退款政策 v2.1", "type", "policy"))
        ));
        return vs;
    }
}
```

**QuestionAnswerAdvisor 内部行为**：before 钩子中取最后一条 user 消息 → `vectorStore.similaritySearch(SearchRequest.query(str).withTopK(4).build())` → 把文档原文 append 到 user 消息。注意是 user 消息非 system——system 是角色设定，不该被每轮检索内容污染。

**关键知识点**：
1. `VectorStore.add()` 内部自动调 `EmbeddingModel.embed()` 计算向量再存。换 PGVector 只需替换一个 `@Bean`。
2. `Document` 有两个字段：`content`（原文）和 `metadata`（任意 KV 元信息）。建库时就带上 `source` 字段。
3. **最大局限**：无多轮上下文理解 — 用户说"刚才那个退款"，直接用原文搜，搜不准。

---

## 5.4 V15 暴露的三个问题

| 问题 | 根因 | 解决 |
|---|---|---|
| ① 多轮检索 query 失效 | `QuestionAnswerAdvisor` 不理解对话上下文 | V17（`RetrievalAugmentationAdvisor` + `CompressionQueryTransformer`） |
| ② 文档入库靠硬编码 | 无 ETL 流水线 | V16 |
| ③ `SimpleVectorStore` 不可持久 + 不支持元数据过滤 | 内存实现，线性扫描 | V16（PGVector） |

问题①是多轮 RAG 最大的工程坑：用户在第 3 轮说"那个 3 个工作日是什么意思"，系统真的去搜"那个 3 个工作日"——在向量空间里这个短语和退款政策文档的相似度极低。专业名称：**多轮 RAG 的指代消解（coreference resolution）**。

---

## 5.5 V16：ETL Pipeline + PGVector

### 5.5.1 ETL 三接口

```
DocumentReader       extends Supplier<List<Document>>                    # Extract
DocumentTransformer  extends Function<List<Document>, List<Document>>    # Transform
DocumentWriter       extends Consumer<List<Document>>                    # Load
```

**`VectorStore` 同时实现了 `DocumentWriter`** — `vectorStore.add(chunks)` 就是 Load。整条流水线三行：

```java
List<Document> docs   = documentReader.get();    // Extract
List<Document> chunks = splitter.apply(docs);     // Transform
vectorStore.add(chunks);                          // Load
```

### 5.5.2 DocumentReader 实现

| 实现类 | 适用格式 | Maven artifactId |
|---|---|---|
| `TikaDocumentReader` | PDF、Word、PPT、HTML、TXT… | `spring-ai-tika-document-reader` |
| `PdfPageDocumentReader` | PDF（每页一个 Document） | `spring-ai-pdf-document-reader` |
| `ParagraphPdfDocumentReader` | PDF（按段落拆分） | `spring-ai-pdf-document-reader` |

多格式混合输入首选 `TikaDocumentReader`。文档结构很重要时优先用 `ParagraphPdfDocumentReader`。

### 5.5.3 TokenTextSplitter 参数

```java
TokenTextSplitter splitter = TokenTextSplitter.builder()
    .chunkSize(512)             // 每块最大 token 数，默认 800
    .minChunkSizeChars(100)     // 低于此字符数的小块合并到相邻块，默认 350
    .minChunkLengthToEmbed(5)   // 低于此 token 数的块直接丢弃，默认 5
    .keepSeparator(true)        // 保留分隔符，保持句子完整，默认 true
    .build();
```

**chunkSize 选型参考**：

| 场景 | chunkSize | 原因 |
|---|---|---|
| 法规/政策文档（段落短） | 256~512 | 每段独立，切小保精度 |
| 技术手册/API 文档 | 512~800 | 段落长，切太小丢上下文 |
| 对话记录/FAQ | 128~256 | 一问一答即语义单元 |
| 代码文件 | 按函数切 | token 切破坏代码逻辑 |

搭配 `topK=4` 时每次塞 prompt 约 2048 tokens。chunkSize 和 topK 是 RAG 链路性价比最高的两个旋钮。

### 5.5.4 PGVector 配置

```yaml
spring:
  datasource:
    url:      jdbc:postgresql://localhost:5432/agent_demo
    username: postgres
    password: ${DB_PASSWORD}
  ai:
    openai:
      embedding:
        options:
          model: text-embedding-3-small   # 1536维
    vectorstore:
      pgvector:
        initialize-schema: true     # 首次启动自建表；生产改 false，由 Flyway 管
        dimensions: 1536            # 必须与 embedding model 输出维度一致
        distance-type: COSINE_DISTANCE
        index-type: HNSW            # 近似最近邻索引，万级以上数据必须开
```

⚠️ **`dimensions` 建表后不能改**（pgvector 0.7 前不支持 `ALTER` 改维度）。换 embedding 模型 = 删表重建 + 全量重入库。必须在 yml 的 `dimensions` 旁写注释标明当前 model。

### 5.5.5 DocumentIngestionService

```java
@Service
@Slf4j
public class DocumentIngestionService {
    private final VectorStore vectorStore;

    public void ingest(Resource resource) {
        List<Document> rawDocs = new TikaDocumentReader(resource).get();
        List<Document> chunks = TokenTextSplitter.builder()
                .chunkSize(512).minChunkSizeChars(100).keepSeparator(true)
                .build().apply(rawDocs);
        vectorStore.add(chunks);
        log.info("入库完成：file={}, rawDocs={}, chunks={}",
                resource.getFilename(), rawDocs.size(), chunks.size());
    }

    public void ingestAll(String classpathPattern) throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources(classpathPattern);
        for (Resource r : resources) ingest(r);
    }
}
```

**设计要点**：`ingest()` 非幂等 — 同一份文档反复调会写入重复 chunk。生产需先清旧数据：按 metadata 过滤搜出旧 chunk id → 批量 `vectorStore.delete(oldIds)` → 再重新入库。体量大时更稳方案：在业务数据库维护 `doc_chunk` 表记录每次入库的 chunk id 列表。

### 5.5.6 V16 查询侧

直接注入 Spring AI 自动配置的 `VectorStore` Bean（现在是 PGVector），`QuestionAnswerAdvisor` 代码一行不动 — VectorStore 抽象的价值：换底层存储不影响查询代码。

---

## 5.6 V17：`RetrievalAugmentationAdvisor` + `CompressionQueryTransformer`

### CompressionQueryTransformer 机制

把 `"对话历史 + 当前问题(含指代词)"` → `"可独立检索的无歧义 query"`。内部发起一次独立 LLM 调用。压缩后的 query 在向量空间里和知识库相关文档语义距离很近。

### 关键依赖：Advisor order 是硬约束

`CompressionQueryTransformer` 从 `ChatClientRequest.messages()` 读对话历史。这些历史是 `MessageChatMemoryAdvisor` 在 before 阶段注入的。因此 **Memory Advisor 必须先于 RAG Advisor 执行**（Memory order=0, RAG order=100）。颠倒顺序不报错，但压缩时 request.messages 只有当前 user 消息——静默退化到 V16 行为。

### 防递归

`CompressionQueryTransformer` 内部调 LLM。若走带 Memory+RAG Advisor 的主 ChatClient 会触发递归。解决方案：给 Transformer 注入**独立的、不挂任何 Advisor 的 `ChatClient.Builder`**。

### RetrievalAugmentationAdvisor 模块化管道

```
QueryTransformer (可插拔) → DocumentRetriever (可替换) → DocumentPostProcessor (可选)
```

| 维度 | `QuestionAnswerAdvisor` | `RetrievalAugmentationAdvisor` |
|---|---|---|
| query 改写 | ❌ | ✅ `CompressionQueryTransformer` |
| 多轮指代消解 | ❌ | ✅ |
| 检索器可替换 | ❌ | ✅ `DocumentRetriever` 接口 |
| 后处理（重排/过滤） | ❌ | ✅ `DocumentPostProcessor` 接口 |

### V17 装配代码

```java
@Configuration
public class V17ChatClientConfig {
    @Bean
    public CompressionQueryTransformer compressionQueryTransformer(
            ChatClient.Builder builder) {
        return CompressionQueryTransformer.builder(builder).build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 ChatMemory memory, VectorStore vectorStore,
                                 CompressionQueryTransformer compressor) {
        return builder
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(memory).build(),  // order=0
                        RetrievalAugmentationAdvisor.builder()
                                .queryTransformers(compressor)
                                .documentRetriever(
                                        VectorStoreDocumentRetriever.builder()
                                                .vectorStore(vectorStore).topK(4).build())
                                .order(100).build()
                ).build();
    }
}
```

### DocumentPostProcessor 扩展

- **相似度过滤**：`VectorStoreDocumentRetriever.similarityThreshold(0.5)` — 中文客服常见阈值起点
- **重排序（Reranking）**：cross-encoder 对 top-K 重打分，需自行实现 `DocumentPostProcessor` 接口

---

## 5.7 V18：三 Advisor 双保险

| Advisor | order | 职责 |
|---|---|---|
| `SummarizingChatMemoryAdvisor` | -100 | 历史超阈值时压缩旧消息，防 token 爆炸 |
| `MessageChatMemoryAdvisor` | 0 | 注入（已压缩的）对话历史 |
| `RetrievalAugmentationAdvisor` | 100 | 基于含摘要的历史压缩 query → 检索知识库 |

**互补原理**：摘要让 RAG 指代消解更可靠——即使用户问"那个退款什么时候到账"，压缩器从摘要里知道"那个"指哪个订单。

```java
@Configuration
public class V18Config {
    @Bean
    public SummarizingChatMemoryAdvisor summarizingAdvisor(ChatMemory memory,
                                                           ChatClient.Builder builder) {
        ChatClient summaryClient = builder.build();   // lean，无 Advisor
        return new SummarizingChatMemoryAdvisor(memory, summaryClient, 30, 10, -100);
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 ChatMemory memory, VectorStore vectorStore,
                                 SummarizingChatMemoryAdvisor summarizingAdvisor,
                                 CompressionQueryTransformer compressor) {
        return builder
                .defaultAdvisors(
                        summarizingAdvisor,
                        MessageChatMemoryAdvisor.builder(memory).build(),
                        RetrievalAugmentationAdvisor.builder()
                                .queryTransformers(compressor)
                                .documentRetriever(VectorStoreDocumentRetriever.builder()
                                        .vectorStore(vectorStore).topK(4).build())
                                .order(100).build()
                ).build();
    }
}
```

---

## 5.8 常见坑

1. **Embedding model 维度与 VectorStore 建表维度不一致**：pgvector 0.7 前不支持 ALTER 改维度。换模型 = 删表重建。必须在 yml 的 `dimensions` 旁写注释标明当前 model。
2. **Memory Advisor 与 RAG Advisor 顺序反了 → 静默退化**：不报错但压缩失效。排查：在 DEBUG 日志搜 `CompressionQueryTransformer` 输出。
3. **文档重复入库**：每次重启无条件 `vectorStore.add()`。解决：入库前先按 source 删除旧 chunk，或改成显式触发的管理接口。
4. **未设 `similarityThreshold`，低相关 chunk 混入 prompt**：推荐 `topK=6, similarityThreshold=0.5`。
5. **知识库无相关文档 → 模型被迫"看空白作文"**：system prompt 加"如果参考资料为空或无相关内容，直接告知用户，不要猜测。"

---

## 5.9 五条心智

1. **RAG 是"空间维度的记忆"，对话记忆是"时间维度的记忆"，两者互补。**
2. **ETL 三接口正交、可替换。** `DocumentReader`/`DocumentTransformer`/`DocumentWriter` 各管一层。
3. **Advisor order 是数据依赖声明，不是建议。** Memory(0) 先于 RAG(100) 是硬依赖。
4. **多轮 RAG 的核心问题是指代消解，`CompressionQueryTransformer` 是生产级解法。**
5. **防递归是写"内部发起 LLM 调用的 Advisor"的铁律。** 每个 `@Bean` 方法参数各自注入独立 prototype `ChatClient.Builder` 是最干净的隔离方式。

### V15 → V18 演化

V15（最简 RAG：`SimpleVectorStore` + `QuestionAnswerAdvisor`，硬编码文档，单轮可用）→ V16（`TikaDocumentReader` + `TokenTextSplitter` + PGVector 持久化，多轮仍翻车）→ V17（`RetrievalAugmentationAdvisor` + `CompressionQueryTransformer`，多轮指代消解）→ V18（三 Advisor 双保险：摘要+记忆+RAG，生产可用）。
