# RAG 主题中块与三层分块优化

> 本文记录本轮 RAG 分块策略优化。阶段编号暂不写死，等作者确认命名后再补入 `AGENTS.md` 文档表。

## 1. 需求分析

目标用户是 B 站内容创作者。当前案例库已经有两层：

- 父块：一条视频案例卡片，承载标题、简介、标签、亮点摘要和数据。
- 子块：一条清洗后的优质评论或弹幕，承载原始观众反馈。

问题是这两层之间缺少“创作者真正会问的主题粒度”。例如“标题包装怎么做”“内容定位像什么”“观众为什么买账”，直接查整张父卡片太粗，直接查单条评论又太碎。

本轮只解决分块粒度问题，不做视频画面理解，不做自动批量采集，不改发布前 Agent。

## 2. 设计方案

新增主题中块，形成三层分块：

| 层级 | 数据表 | 作用 |
|---|---|---|
| 父视频块 | `creator_reference_video` | 回到完整案例上下文 |
| 主题中块 | `creator_reference_video_chunk` | 承载标题包装、内容定位、观众反馈主题 |
| 原始证据小块 | `creator_reference_video_item` | 承载评论 / 弹幕原文证据 |

主题中块类型先固定三类，但不是每条视频都会强行生成三类：

- `TITLE_PACKAGE`：标题、标签、分区、层级、简介摘录。
- `CONTENT_POSITIONING`：简介、亮点摘要、分区、标签、数据表现。
- `AUDIENCE_FEEDBACK_SUMMARY`：亮点摘要和最多 12 条已清洗观众反馈。

其中 `AUDIENCE_FEEDBACK_SUMMARY` 依赖已清洗的评论或弹幕。没有反馈素材的视频只生成前两类，避免为了凑块生成空内容。

中块不调用 LLM 生成新结论，只从已入库材料确定性拼装。这样做的原因是：本轮要解决的是“召回粒度”，不是新增一个可能编造的摘要层。

## 3. 实现范围

后端新增：

- `creator_reference_video_chunk` 表。
- `ReferenceVideoChunkRecord`、`ReferenceVideoChunkIndexRow`。
- `KnowledgeReferenceChunkService`：导入时生成主题中块。
- `KnowledgeReferenceChunkIndexService`：主题中块增量索引和状态查询。
- `KnowledgeVectorStore` 增加独立中块集合 `creator_reference_video_chunk`。
- `KnowledgeReferenceRetrievalService` 增加中块召回，并把命中的 `videoId` 合并回父表。

接口新增：

```text
POST /api/knowledge/reference-videos/index/chunks/rebuild
GET  /api/knowledge/reference-videos/index/chunks/status
```

检索合并顺序：

```text
父视频命中 -> 主题中块命中 -> 原始证据小块命中 -> 回查父表 -> rerank -> 返回原响应结构
```

响应结构不变。中块只提升召回，不新增前端展示字段。

## 4. 边界

- hybrid 开启时暂不混入中块 dense 集合。原因是当前 `HYBRID` 模式表示父 / 子 hybrid 集合的 dense+BM25 召回，混入中块 dense 会让模式含义不清。后续如果需要，再单独做“中块 hybrid 集合”。
- 老数据无需重新导入。中块索引重建前会为缺少可生成中块的历史视频做一次确定性补齐。
- 新导入视频会同步生成中块。

## 5. 测试验证点

编译和运行由作者执行。建议验证：

```bash
cd backend
mvn -q -DskipTests compile
```

默认 RAG 关闭时：

- `GET /api/knowledge/reference-videos/index/chunks/status` 返回 200，`ragEnabled=false`。
- `POST /api/knowledge/reference-videos/index/chunks/rebuild` 返回 400，提示 RAG 未启用或中块向量库未就绪。

开启 RAG + Milvus + Embedding 后：

- 新导入单 BV 后，数据库应有对应 `creator_reference_video_chunk` 记录。
- 对老案例直接调用 `/index/chunks/rebuild`，应先补齐可生成的中块，再索引 `PENDING / FAILED` 中块。
- 搜索“标题包装”“内容定位”“观众反馈”等问题时，中块命中的案例能并入最终候选。
