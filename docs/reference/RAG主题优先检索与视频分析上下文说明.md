# RAG 主题优先检索与视频分析上下文说明

主题优先检索的核心是：RAG 只负责找“相关主题”，视频展示顺序交给质量分。

## 接口

```text
POST /api/knowledge/reference-videos/topic-search
GET  /api/knowledge/reference-videos/{videoId}/analysis-context
```

## topic-search

请求字段：

- `query`：必填。
- `category`：可选分区过滤。
- `tier`：可选层级过滤。
- `page`：可选，1 到 4。
- `size`：可选，1 到 5，默认 5。
- `strategy`：可选，`NONE` / `REWRITE` / `HYDE` / `MULTI_QUERY`。

处理流程：

```text
query
-> 查询增强
-> 检索主题中块集合
   -> 中块异常 / 零命中时退回 SQL 关键词兜底
-> 读取 metadata.videoId
-> 去重取最多 20 个视频
-> MySQL 二次过滤 category / tier
   -> 父表回查为空时退回 SQL 关键词兜底
-> 按 quality_score 形成候选池
-> 在 top20 视频内检索相关评论弹幕
-> rerank 开启时结合相关评论弹幕对 top20 候选精排
-> 分页返回当前批次
```

返回字段：

- `mode`：`TOPIC_VECTOR` 或 `SQL`。
- `matchedTopics`：当前页卡片对应的命中主题。
- `evidence`：当前页卡片对应的相关评论 / 弹幕。
- `cards`：当前页视频卡片。
- `hasMore`：是否还能刷新下一页。
- `reranked`：是否经过 qwen3-rerank 精排。关闭、失败或 SQL 兜底时为 `false`。

rerank 文本由父视频卡片、命中主题和相关评论 / 弹幕组成。
这样排序能优先看观众真实反馈，而不是只看标题和主题摘要。
子向量集合可用时，评论弹幕按 query 语义检索；不可用时才退回少量代表证据。
完整评论弹幕不会在检索阶段全部塞入模型，用户点击卡片后才由 `analysis-context` 加载。

注意：`topic-search` 依赖的是主题中块集合，不是父表案例卡片集合。
前端索引面板需要同时关注“主题中块索引”的状态。
如果中块集合未索引或零命中，接口会退回 SQL 关键词兜底。
SQL 兜底会把 query 拆成关键词片段，避免标题里的空格、括号、斜杠差异导致明明存在的案例搜不到。

## analysis-context

点击视频卡片后调用。

返回：

- `video`：父视频卡片。
- `topics`：该视频所有主题中块。
- `evidenceItems`：该视频前 30 条评论 / 弹幕证据。

这个接口直接查 MySQL，不要求再次向量检索。
