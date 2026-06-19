# RAG 主题优先检索与视频分析上下文

> 本文记录主题优先检索链路。阶段编号暂不写死，等作者确认命名后再补入 `AGENTS.md` 文档表。

## 1. 需求分析

目标用户是 B 站内容创作者。

现有父块 / 中块 / 子块并行召回可以找到案例，但父块命中只能说明“整条视频大概相关”，不能说明“它在哪个创作主题上相关”。这会让视频卡片缺少解释力。

本轮改成主题优先：

```text
先 RAG 检索主题中块
-> 聚合命中的 videoId
-> 命中视频先按 quality_score 形成 top20 候选
-> 在 top20 视频内按 query 检索相关评论弹幕
-> rerank 开启时用 query + 视频卡片 + 命中主题 + 相关评论弹幕精排 top20
-> 用户点击卡片后再加载该视频的主题中块和评论弹幕上下文
```

本轮只做后端接口，不改前端 AI 交互台。

## 2. 设计方案

新增两个接口：

```text
POST /api/knowledge/reference-videos/topic-search
GET  /api/knowledge/reference-videos/{videoId}/analysis-context
```

`topic-search` 的职责：

- 用 query 检索 `creator_reference_video_chunk` 中块集合。
- 从命中的中块 metadata 读取 `videoId`。
- 最多保留 20 个候选视频。
- 回查 MySQL 时按 `category` / `tier` 二次过滤，再按 `quality_score DESC, id DESC` 形成候选池。
- 在 top20 候选视频范围内检索子向量集合，找到和 query 最相关的评论 / 弹幕。
- 如果运行期 `knowledge.rag.rerank.enabled=true`，用原始 query 对 top20 候选做 qwen3-rerank 精排，精排文本包含视频卡片、命中主题和相关评论 / 弹幕，失败则保持质量分顺序。
- 默认每页 5 张卡片，最多 4 页，也就是最多展示 top20。

`analysis-context` 的职责：

- 用户点击某张视频卡片后，按 `videoId` 直接读取 MySQL。
- 返回父视频卡片、该视频的主题中块、该视频的评论弹幕证据。
- 不要求用户手动开启 RAG，因为点击后已经有明确视频范围。

## 3. 实现范围

新增模型：

- `ReferenceVideoTopicSearchRequest`
- `ReferenceVideoTopicSearchResponse`
- `ReferenceVideoMatchedTopic`
- `ReferenceVideoAnalysisContextResponse`

新增服务：

- `KnowledgeReferenceTopicSearchService`

Mapper 新增：

- `listByVideoIdsOrderByQuality`
- `listChunksByVideoId`
- `listEvidenceItemsByVideoId`

控制器新增：

- `POST /topic-search`
- `GET /{videoId}/analysis-context`

## 4. 边界

- RAG 关闭或中块向量库不可用时，`topic-search` 退回 SQL 质量分兜底。
- `matchedTopics` 只返回当前页卡片关联的主题中块，避免前端展示还没出现的视频解释。
- 主题中块向量召回先取 60 个中块文档，再收敛成最多 20 个唯一视频，避免一个视频多中块占满候选。
- 向量 metadata 可能滞后于 MySQL，所以视频卡片回查会再次按分区和层级过滤。
- rerank 只调整 top20 候选顺序，不改变候选集合；关闭、未配置 key 或调用失败时返回 `reranked=false`，继续按质量分展示。
- 子向量集合可用时，每个候选视频最多取 4 条和 query 相关的评论 / 弹幕；子向量不可用时才退回每来源 2 条代表证据。
- `evidence` 只返回当前页卡片对应的相关评论 / 弹幕，完整评论弹幕仍在用户点击卡片后的 `analysis-context` 中加载。
- `analysis-context` 直接读 MySQL 事实源，不做向量检索。

## 5. 测试验证点

编译由作者执行：

```bash
cd backend
mvn -q -DskipTests compile
```

接口验证：

- `POST /api/knowledge/reference-videos/topic-search`，传 `page=1` 返回前 5 张卡片。
- 同 query 传 `page=2/3/4` 分别返回后续批次。
- 子条目向量库可用时，响应 `evidence` 返回当前页卡片对应的相关评论 / 弹幕。
- `GET /api/knowledge/reference-videos/{videoId}/analysis-context` 返回该视频主题和评论弹幕上下文。
- RAG 关闭时 `topic-search` 不报错，返回 `mode=SQL`。
- 设置页打开 `knowledge.rag.rerank.enabled` 且配置 rerank key 后，`topic-search` 返回 `reranked=true`；关闭或调用失败时为 `false`。
